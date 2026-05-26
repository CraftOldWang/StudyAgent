package com.studyagent.infrastructure.search;

public record IndexedChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long userId,
        Integer chunkIndex,
        String content,
        float[] embedding
) {
}
