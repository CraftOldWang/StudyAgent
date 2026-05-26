package com.studyagent.modules.rag.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequest(
        Long knowledgeBaseId,
        List<Long> knowledgeBaseIds,
        @NotBlank String question
) {
}
