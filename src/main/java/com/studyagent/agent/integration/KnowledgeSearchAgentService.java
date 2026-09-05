package com.studyagent.agent.integration;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.KnowledgeSearchAgentConfiguration;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class KnowledgeSearchAgentService {

    private final ReActAgent agent;

    public KnowledgeSearchAgentService(
            @Qualifier(KnowledgeSearchAgentConfiguration.AGENT_BEAN_NAME)
            ReActAgent agent
    ) {
        this.agent = agent;
    }

    public AgentSearchResponse answer(Long userId, Long knowledgeBaseId, String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索问题不能为空");
        }
        String normalizedQuery = query.trim();
        KnowledgeSearchScope scope = new KnowledgeSearchScope(userId, knowledgeBaseId);
        KnowledgeSearchExecution execution = new KnowledgeSearchExecution();
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId.toString())
                .sessionId(UUID.randomUUID().toString())
                .put(KnowledgeSearchScope.class, scope)
                .put(KnowledgeSearchExecution.class, execution)
                .build();
        Msg response = agent.call(normalizedQuery, context).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new BusinessException("DeepSeek 未返回知识库回答");
        }
        List<KnowledgeSearchResponse.Result> hits = execution.hits();
        boolean toolInvoked = execution.invoked();
        String answer = !toolInvoked || hits.isEmpty()
                ? KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE
                : response.getTextContent();
        return new AgentSearchResponse(
                normalizedQuery,
                answer,
                toolInvoked,
                hits);
    }

    public record AgentSearchResponse(
            String query,
            String answer,
            boolean toolInvoked,
            List<KnowledgeSearchResponse.Result> hits
    ) {
    }
}
