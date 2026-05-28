package com.studyagent.modules.rag.domain;

import java.util.List;

/**
 * RAG 检索结果，返回原问题和排序后的引用列表。
 */
public record RagSearchResult(
        String question,
        List<RagReference> references
) {
}
