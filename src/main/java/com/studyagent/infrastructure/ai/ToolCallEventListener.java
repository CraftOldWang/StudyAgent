package com.studyagent.infrastructure.ai;

/**
 * 模型工具调用事件监听器。
 *
 * <p>Spring AI 可以在模型内部自动执行 tool calling，但学习 Agent 的前端需要看到工具状态，
 * 例如正在检索知识库、复习卡写入成功或失败。因此这里定义一个极薄的监听接口，让基础设施层
 * 在执行 ToolCallback 前后把事件回传给应用服务，再由应用服务转成 SSE。</p>
 */
public interface ToolCallEventListener {

    /**
     * 工具即将执行。
     */
    default void onToolStarted(String toolName, String argumentsJson) {
    }

    /**
     * 工具执行成功。
     */
    default void onToolCompleted(String toolName, String result) {
    }

    /**
     * 工具执行失败。
     */
    default void onToolFailed(String toolName, String argumentsJson, String errorMessage) {
    }

    /**
     * 无事件监听场景使用的空实现。
     */
    static ToolCallEventListener noop() {
        return new ToolCallEventListener() {
        };
    }
}
