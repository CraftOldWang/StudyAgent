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

@Service
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    public static final String TOOL_NAME = "knowledge_search";

    private final RagService ragService;
    private final ToolCallRecordMapper toolCallRecordMapper;
    private final ObjectMapper objectMapper;

    public RagSearchResult search(
            Long agentRunId,
            Long sessionId,
            Long userId,
            List<Long> allowedKnowledgeBaseIds,
            String question
    ) {
        ToolCallRecord record = createRecord(agentRunId, sessionId, userId, allowedKnowledgeBaseIds, question);
        try {
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

    private void completeRecord(ToolCallRecord record, List<RagReference> references) {
        record.setStatus("COMPLETED");
        record.setResultSummary("hitCount=" + references.size());
        record.setFinishedAt(LocalDateTime.now());
        toolCallRecordMapper.updateById(record);
    }

    private void failRecord(ToolCallRecord record, String errorMessage) {
        record.setStatus("FAILED");
        record.setErrorMessage(errorMessage);
        record.setFinishedAt(LocalDateTime.now());
        toolCallRecordMapper.updateById(record);
    }

    private String argumentsJson(List<Long> allowedKnowledgeBaseIds, String question) {
        try {
            return objectMapper.writeValueAsString(new KnowledgeSearchArguments(allowedKnowledgeBaseIds, question));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("工具参数序列化失败: " + ex.getMessage());
        }
    }

    private record KnowledgeSearchArguments(
            List<Long> knowledgeBaseIds,
            String question
    ) {
    }
}
