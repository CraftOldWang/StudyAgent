package com.studyagent.learning.web;

import java.util.List;

public final class LearningMutationResponse {

    private LearningMutationResponse() {
    }

    public record Created(String traceId, LearningSessionResponse session) {
    }

    public record QuizGenerated(String traceId, LearningSessionResponse.QuizResponse quiz, LearningSessionResponse session) {
    }

    public record QuizResult(
            String traceId,
            Long quizId,
            int score,
            List<LearningSessionResponse.FeedbackResponse> feedback,
            LearningSessionResponse session) {
    }

    public record CardsGenerated(
            String traceId,
            Long knowledgePointId,
            List<LearningSessionResponse.CardResponse> cards,
            LearningSessionResponse session) {
    }
}
