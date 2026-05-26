package com.studyagent.infrastructure.search;

public record SearchHitChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long userId,
        Long parentChunkId,
        Integer chunkIndex,
        String documentTitle,
        String content,
        String metadataJson,
        double score
) {
}
