package com.studyagent.modules.learning.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 学习 Agent 继续会话请求。
 */
public record LearningChatRequest(
        @NotBlank String message
) {
}
