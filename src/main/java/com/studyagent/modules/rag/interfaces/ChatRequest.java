package com.studyagent.modules.rag.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * RAG 聊天请求，支持单知识库和多知识库范围两种写法。
 */
public record ChatRequest(
        Long knowledgeBaseId,
        List<Long> knowledgeBaseIds,
        @NotBlank String question
) {
}
