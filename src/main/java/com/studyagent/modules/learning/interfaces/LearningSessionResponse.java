package com.studyagent.modules.learning.interfaces;

public record LearningSessionResponse(
        Long sessionId,
        Long agentRunId,
        String status
) {
}
