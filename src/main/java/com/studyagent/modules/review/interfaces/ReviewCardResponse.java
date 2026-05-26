package com.studyagent.modules.review.interfaces;

import com.studyagent.modules.review.domain.ReviewCard;
import java.time.LocalDateTime;

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
