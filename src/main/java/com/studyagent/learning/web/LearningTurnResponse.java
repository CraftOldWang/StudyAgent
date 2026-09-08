package com.studyagent.learning.web;

public record LearningTurnResponse(
        String traceId,
        String answer,
        LearningSessionResponse session,
        com.studyagent.model.LearningTurn turn) {
    public LearningTurnResponse(String traceId, String answer, LearningSessionResponse session) {
        this(traceId, answer, session, null);
    }
}
