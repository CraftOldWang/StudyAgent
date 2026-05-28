package com.studyagent.modules.review.interfaces;

import com.studyagent.modules.review.domain.ReviewCard;
import java.time.LocalDateTime;

/**
 * 复习卡响应，包含卡片内容和当前 FSRS 调度状态。
 */
public record ReviewCardResponse(
        Long id,
        Long knowledgeBaseId,
        Long documentId,
        Long sessionId,
        String front,
        String back,
        String tagsJson,
        String status,
        String cardState,
        LocalDateTime dueAt,
        LocalDateTime lastReviewedAt,
        Double stability,
        Double difficulty,
        Integer elapsedDays,
        Integer scheduledDays,
        Integer reps,
        Integer lapses
) {

    /**
     * 将复习卡实体转换为接口响应。
     */
    public static ReviewCardResponse from(ReviewCard card) {
        return new ReviewCardResponse(
                card.getId(),
                card.getKnowledgeBaseId(),
                card.getDocumentId(),
                card.getSessionId(),
                card.getFront(),
                card.getBack(),
                card.getTagsJson(),
                card.getStatus(),
                card.getCardState(),
                card.getDueAt(),
                card.getLastReviewedAt(),
                card.getStability(),
                card.getDifficulty(),
                card.getElapsedDays(),
                card.getScheduledDays(),
                card.getReps(),
                card.getLapses()
        );
    }
}
