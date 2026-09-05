package com.studyagent.learning.web;

public record LearningTurnResponse(
        String traceId,
        String answer,
        LearningSessionResponse session) {
}
