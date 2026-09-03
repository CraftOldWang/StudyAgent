package com.studyagent.agent.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 在构建 AgentScope RuntimeContext 前验证单知识库范围。
 */
@Component
@RequiredArgsConstructor
public final class AgentInvocationScopeFactory {

    private final DocumentMapper documentMapper;

    public AgentInvocationScope create(Long userId, Long knowledgeBaseId, Long knowledgePointId) {
        validateRequiredIds(userId, knowledgeBaseId, knowledgePointId);
        Long matchingDocuments = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId));
        if (matchingDocuments == null || matchingDocuments == 0) {
            throw new BusinessException("当前用户无可用的知识库文档: knowledgeBaseId=" + knowledgeBaseId);
        }
        return new AgentInvocationScope(userId, knowledgeBaseId, knowledgePointId);
    }

    public RuntimeContext createRuntimeContext(
            String sessionId,
            Long userId,
            Long knowledgeBaseId,
            Long knowledgePointId
    ) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException("Agent 调用缺少 sessionId");
        }
        AgentInvocationScope scope = create(userId, knowledgeBaseId, knowledgePointId);
        return RuntimeContext.builder()
                .userId(scope.userId().toString())
                .sessionId(sessionId)
                .put(AgentInvocationScope.class, scope)
                .build();
    }

    private void validateRequiredIds(Long userId, Long knowledgeBaseId, Long knowledgePointId) {
        if (userId == null) {
            throw new BusinessException("userId 不能为空");
        }
        if (knowledgeBaseId == null) {
            throw new BusinessException("knowledgeBaseId 不能为空");
        }
        if (knowledgePointId == null) {
            throw new BusinessException("knowledgePointId 不能为空");
        }
    }
}
