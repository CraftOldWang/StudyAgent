package com.studyagent.modules.tool.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.rag.application.RagService;
import com.studyagent.modules.rag.domain.RagReference;
import com.studyagent.modules.rag.domain.RagSearchResult;
import com.studyagent.modules.tool.domain.ToolCallRecord;
import com.studyagent.modules.tool.infrastructure.ToolCallRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 的知识库检索工具，封装资源范围校验、RAG 检索和工具调用审计。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    public static final String TOOL_NAME = "knowledge_search";

    private final RagService ragService;
    private final ToolCallRecordMapper toolCallRecordMapper;
    private final ObjectMapper objectMapper;

    /**
     * 在当前会话允许的知识库范围内检索资料。
     */
    public RagSearchResult search(
            Long agentRunId,
            Long sessionId,
            Long userId,
            List<Long> allowedKnowledgeBaseIds,
            String question
    ) {
        ToolCallRecord record = createRecord(agentRunId, sessionId, userId, allowedKnowledgeBaseIds, question);
        try {
            // 工具只能使用会话预先授权的知识库范围，不能让模型临时决定检索范围。
            if (allowedKnowledgeBaseIds == null || allowedKnowledgeBaseIds.isEmpty()) {
                throw new BusinessException("当前会话没有可检索的知识库范围");
            }
            RagSearchResult result = ragService.search(userId, allowedKnowledgeBaseIds, question);
            completeRecord(record, result.references());
            return result;
        } catch (RuntimeException ex) {
            failRecord(record, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 创建 RUNNING 审计记录，先落库再执行真实工具逻辑。
     */
    private ToolCallRecord createRecord(
            Long agentRunId,
            Long sessionId,
            Long userId,
            List<Long> allowedKnowledgeBaseIds,
            String question
    ) {
        ToolCallRecord record = new ToolCallRecord();
        record.setAgentRunId(agentRunId);
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setToolName(TOOL_NAME);
        record.setArgumentsJson(argumentsJson(allowedKnowledgeBaseIds, question));
        record.setStatus("RUNNING");
        record.setPermissionChecked(true);
        record.setCreatedAt(LocalDateTime.now());
        toolCallRecordMapper.insert(record);
        return record;
    }

    /**
     * 工具成功后记录命中数量。
     */
    private void completeRecord(ToolCallRecord record, List<RagReference> references) {
        record.setStatus("COMPLETED");
        record.setResultSummary("hitCount=" + references.size());
        record.setFinishedAt(LocalDateTime.now());
        toolCallRecordMapper.updateById(record);
    }

    /**
     * 工具失败后记录错误，调用方继续抛出异常并通过 SSE 暴露失败事件。
     */
    private void failRecord(ToolCallRecord record, String errorMessage) {
        record.setStatus("FAILED");
        record.setErrorMessage(errorMessage);
        record.setFinishedAt(LocalDateTime.now());
        toolCallRecordMapper.updateById(record);
    }

    /**
     * 将工具参数序列化为 JSON，便于审计和问题回放。
     */
    private String argumentsJson(List<Long> allowedKnowledgeBaseIds, String question) {
        try {
            return objectMapper.writeValueAsString(new KnowledgeSearchArguments(allowedKnowledgeBaseIds, question));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("工具参数序列化失败: " + ex.getMessage());
        }
    }

    /**
     * knowledge_search 工具的审计参数快照。
     */
    private record KnowledgeSearchArguments(
            List<Long> knowledgeBaseIds,
            String question
    ) {
    }
}
