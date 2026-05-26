package com.studyagent.modules.tool.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.review.application.ReviewCardService;
import com.studyagent.modules.review.interfaces.ReviewCardCreateRequest;
import com.studyagent.modules.review.interfaces.ReviewCardResponse;
import com.studyagent.modules.tool.domain.ToolCallRecord;
import com.studyagent.modules.tool.infrastructure.ToolCallRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewCardWriteTool {

    public static final String TOOL_NAME = "review_card_write";

    private final ReviewCardService reviewCardService;
    private final ToolCallRecordMapper toolCallRecordMapper;
    private final ObjectMapper objectMapper;

    public List<ReviewCardResponse> writeCards(
            Long agentRunId,
            Long sessionId,
            Long userId,
            List<Long> allowedKnowledgeBaseIds,
            List<CardDraft> drafts
    ) {
        ToolCallRecord record = createRecord(agentRunId, sessionId, userId, allowedKnowledgeBaseIds, drafts);
        try {
            if (drafts == null || drafts.isEmpty()) {
                throw new BusinessException("复习卡草稿不能为空");
            }
            List<ReviewCardResponse> cards = drafts.stream()
                    .map(draft -> createCard(sessionId, allowedKnowledgeBaseIds, draft))
                    .toList();
            completeRecord(record, cards.size());
            return cards;
        } catch (RuntimeException ex) {
            failRecord(record, ex.getMessage());
            throw ex;
        }
    }

    private ReviewCardResponse createCard(Long sessionId, List<Long> allowedKnowledgeBaseIds, CardDraft draft) {
        Long knowledgeBaseId = draft.knowledgeBaseId();
        if (knowledgeBaseId == null) {
            knowledgeBaseId = allowedKnowledgeBaseIds.getFirst();
        }
        if (!allowedKnowledgeBaseIds.contains(knowledgeBaseId)) {
            throw new BusinessException("复习卡写入越权，知识库不在当前会话范围内: " + knowledgeBaseId);
        }
        return reviewCardService.create(new ReviewCardCreateRequest(
                knowledgeBaseId,
                draft.documentId(),
                sessionId,
                draft.front(),
                draft.back(),
                draft.tags(),
                draft.sourceMessageId(),
                draft.sourceChunkIds()
        ));
    }

    private ToolCallRecord createRecord(
            Long agentRunId,
            Long sessionId,
            Long userId,
            List<Long> allowedKnowledgeBaseIds,
            List<CardDraft> drafts
    ) {
        ToolCallRecord record = new ToolCallRecord();
        record.setAgentRunId(agentRunId);
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setToolName(TOOL_NAME);
        record.setArgumentsJson(argumentsJson(allowedKnowledgeBaseIds, drafts));
        record.setStatus("RUNNING");
        record.setPermissionChecked(true);
        record.setCreatedAt(LocalDateTime.now());
        toolCallRecordMapper.insert(record);
        return record;
    }

    private void completeRecord(ToolCallRecord record, int cardCount) {
        record.setStatus("COMPLETED");
        record.setResultSummary("cardCount=" + cardCount);
        record.setFinishedAt(LocalDateTime.now());
        toolCallRecordMapper.updateById(record);
    }

    private void failRecord(ToolCallRecord record, String errorMessage) {
        record.setStatus("FAILED");
        record.setErrorMessage(errorMessage);
        record.setFinishedAt(LocalDateTime.now());
        toolCallRecordMapper.updateById(record);
    }

    private String argumentsJson(List<Long> allowedKnowledgeBaseIds, List<CardDraft> drafts) {
        try {
            return objectMapper.writeValueAsString(new ReviewCardWriteArguments(allowedKnowledgeBaseIds, drafts));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("工具参数序列化失败: " + ex.getMessage());
        }
    }

    public record CardDraft(
            Long knowledgeBaseId,
            Long documentId,
            String front,
            String back,
            List<String> tags,
            Long sourceMessageId,
            List<Long> sourceChunkIds
    ) {
    }

    private record ReviewCardWriteArguments(
            List<Long> knowledgeBaseIds,
            List<CardDraft> drafts
    ) {
    }
}
