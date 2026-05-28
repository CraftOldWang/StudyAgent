package com.studyagent.modules.rag.domain;

/**
 * RAG 引用片段，记录 chunk、文档、召回来源和融合分数。
 */
public record RagReference(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long parentChunkId,
        Integer chunkIndex,
        String documentTitle,
        String content,
        String retrievalSource,
        double score
) {
}
