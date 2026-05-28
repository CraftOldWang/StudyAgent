package com.studyagent.modules.tool.application;

import com.studyagent.common.exception.BusinessException;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 工具调用的服务端上下文。
 *
 * <p>模型只能决定“要查什么、要写什么卡片”，不能决定 userId、sessionId 或知识库授权范围。
 * 因此这些安全敏感字段统一由后端在调用 ChatClient 时放入 ToolContext，工具执行时再从这里读取。</p>
 */
public record LearningAgentToolContext(
        Long agentRunId,
        Long sessionId,
        Long userId,
        List<Long> allowedKnowledgeBaseIds
) {

    public static final String CONTEXT_KEY = "learningAgentToolContext";

    public LearningAgentToolContext {
        if (agentRunId == null) {
            throw new BusinessException("工具上下文缺少 agentRunId");
        }
        if (sessionId == null) {
            throw new BusinessException("工具上下文缺少 sessionId");
        }
        if (userId == null) {
            throw new BusinessException("工具上下文缺少 userId");
        }
        if (allowedKnowledgeBaseIds == null || allowedKnowledgeBaseIds.isEmpty()) {
            throw new BusinessException("工具上下文缺少知识库授权范围");
        }
        allowedKnowledgeBaseIds = List.copyOf(allowedKnowledgeBaseIds);
    }

    /**
     * 生成 ChatClient.toolContext(...) 可直接使用的 Map。
     */
    public Map<String, Object> toToolContextMap() {
        return Map.of(CONTEXT_KEY, this);
    }
}
