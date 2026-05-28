package com.studyagent.modules.learning.interfaces;

/**
 * 学习会话创建响应。
 */
public record LearningSessionResponse(
        Long sessionId,
        Long agentRunId,
        String status
) {
}
