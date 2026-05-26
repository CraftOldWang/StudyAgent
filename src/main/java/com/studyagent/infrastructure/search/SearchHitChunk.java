package com.studyagent.infrastructure.search;

public record SearchHitChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Integer chunkIndex,
        String content,
        double score
) {
}
