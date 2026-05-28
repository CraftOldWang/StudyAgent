package com.studyagent.modules.tool.application;

import com.studyagent.common.exception.BusinessException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 从 Spring AI ToolContext 中解析学习 Agent 的服务端上下文。
 */
@Component
public class LearningAgentToolContextResolver {

    /**
     * 读取本轮工具调用上下文；缺失时立即失败，避免工具在未知权限范围下执行。
     */
    public LearningAgentToolContext require(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new BusinessException("工具调用缺少服务端上下文");
        }
        Object value = toolContext.getContext().get(LearningAgentToolContext.CONTEXT_KEY);
        if (value instanceof LearningAgentToolContext context) {
            return context;
        }
        throw new BusinessException("工具调用上下文类型错误");
    }
}
