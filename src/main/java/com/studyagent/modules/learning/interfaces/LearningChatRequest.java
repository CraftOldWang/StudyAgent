package com.studyagent.modules.learning.interfaces;

import jakarta.validation.constraints.NotBlank;

public record LearningChatRequest(
        @NotBlank String message
) {
}
