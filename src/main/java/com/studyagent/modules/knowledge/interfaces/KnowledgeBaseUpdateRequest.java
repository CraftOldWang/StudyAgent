package com.studyagent.modules.knowledge.interfaces;

public record KnowledgeBaseUpdateRequest(
        String name,
        String description,
        String status
) {
}
