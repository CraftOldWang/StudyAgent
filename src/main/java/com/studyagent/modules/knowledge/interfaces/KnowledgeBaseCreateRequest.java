package com.studyagent.modules.knowledge.interfaces;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeBaseCreateRequest(
        @NotBlank String name,
        String description
) {
}
