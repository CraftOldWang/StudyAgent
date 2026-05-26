package com.studyagent.infrastructure.search;

public record IndexedChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long userId,
        Long parentChunkId,
        Integer chunkIndex,
        String documentTitle,
        String content,
        String metadataJson,
        float[] embedding
) {
}
