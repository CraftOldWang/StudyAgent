package com.studyagent.infrastructure.ai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 带事件观测的 Spring AI ToolCallback 包装器。
 *
 * <p>它不改变工具业务行为，只在调用前后通知监听器。真实权限校验、审计落库、业务执行都仍然在
 * 原始工具里完成，这样可以避免应用服务为了发 SSE 而绕过统一工具治理。</p>
 */
public class ObservedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolCallEventListener eventListener;

    public ObservedToolCallback(ToolCallback delegate, ToolCallEventListener eventListener) {
        this.delegate = delegate;
        this.eventListener = eventListener == null ? ToolCallEventListener.noop() : eventListener;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = getToolDefinition().name();
        eventListener.onToolStarted(toolName, toolInput);
        try {
            String result = delegate.call(toolInput, toolContext);
            eventListener.onToolCompleted(toolName, result);
            return result;
        } catch (RuntimeException ex) {
            eventListener.onToolFailed(toolName, toolInput, ex.getMessage());
            throw ex;
        }
    }
}
