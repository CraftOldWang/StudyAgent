package com.studyagent.agent.integration;

import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.agent.RuntimeContext;

/**
 * 服务端为一次 M1 知识库检索绑定的权限范围。
 */
public record KnowledgeSearchScope(Long userId, Long knowledgeBaseId) {

    public KnowledgeSearchScope {
        if (userId == null || knowledgeBaseId == null) {
            throw new BusinessException("知识检索 scope 缺少 userId 或 knowledgeBaseId");
        }
    }

    public static KnowledgeSearchScope require(RuntimeContext context) {
        if (context == null) {
            throw new BusinessException("知识检索缺少 RuntimeContext");
        }
        KnowledgeSearchScope scope = context.get(KnowledgeSearchScope.class);
        if (scope == null) {
            throw new BusinessException("知识检索缺少服务端 scope");
        }
        return scope;
    }
}
