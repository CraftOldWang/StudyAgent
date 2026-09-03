package com.studyagent.modules.rag.domain;

/**
 * RAG 引用片段，记录 chunk、文档、召回来源和融合分数。
 */
public record RagReference(
        String chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String parentChunkId,
        Integer chunkIndex,
        String documentTitle,
        String content,
        String retrievalSource,
        double score
) {
}
