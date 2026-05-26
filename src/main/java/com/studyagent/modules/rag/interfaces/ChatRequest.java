package com.studyagent.modules.rag.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatRequest(
        @NotNull Long knowledgeBaseId,
        @NotBlank String question
) {
}
