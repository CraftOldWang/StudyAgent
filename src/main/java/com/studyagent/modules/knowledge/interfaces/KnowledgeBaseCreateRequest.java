package com.studyagent.modules.knowledge.interfaces;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建知识库请求。
 */
public record KnowledgeBaseCreateRequest(
        @NotBlank String name,
        String description
) {
}
