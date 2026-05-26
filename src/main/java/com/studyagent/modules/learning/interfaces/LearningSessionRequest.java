package com.studyagent.modules.learning.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record LearningSessionRequest(
        @NotBlank String message,
        @NotEmpty List<Long> knowledgeBaseIds
) {
}
