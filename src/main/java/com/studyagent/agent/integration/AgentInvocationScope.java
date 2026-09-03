package com.studyagent.agent.integration;

import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.agent.RuntimeContext;

/**
 * 已由服务端验证并贯穿一次 AgentScope 调用的业务范围。
 */
public record AgentInvocationScope(
        Long userId,
        Long knowledgeBaseId,
        Long knowledgePointId
) {

    public AgentInvocationScope {
        if (userId == null) {
            throw new BusinessException("Agent 调用 scope 缺少 userId");
        }
        if (knowledgeBaseId == null) {
            throw new BusinessException("Agent 调用 scope 缺少 knowledgeBaseId");
        }
        if (knowledgePointId == null) {
            throw new BusinessException("Agent 调用 scope 缺少 knowledgePointId");
        }
    }

    /**
     * 从服务端创建的 typed RuntimeContext 中读取 scope；不接受模型输入的权限字段。
     */
    public static AgentInvocationScope require(RuntimeContext context) {
        if (context == null) {
            throw new BusinessException("Agent 调用缺少 RuntimeContext");
        }
        AgentInvocationScope scope = context.get(AgentInvocationScope.class);
        if (scope == null) {
            throw new BusinessException("Agent 调用缺少服务端 scope");
        }
        return scope;
    }
}
