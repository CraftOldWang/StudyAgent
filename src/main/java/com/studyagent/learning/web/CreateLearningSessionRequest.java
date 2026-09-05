package com.studyagent.learning.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateLearningSessionRequest(
        @NotNull Long knowledgeBaseId,
        @NotBlank String learningGoal) {
}
