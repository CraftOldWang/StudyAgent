package com.studyagent.modules.rag.domain;

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
