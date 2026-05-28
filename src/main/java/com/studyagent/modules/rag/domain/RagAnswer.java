package com.studyagent.modules.rag.domain;

import java.util.List;

/**
 * RAG 问答结果，包含生成答案和支撑该答案的知识库引用。
 */
public record RagAnswer(
        String answer,
        List<RagReference> references
) {
}
