package com.studyagent.modules.learning.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 测验作答请求。
 */
public record QuizAnswerRequest(
        @NotBlank String userAnswer
) {
}
