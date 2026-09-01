package com.studyagent.infrastructure.search;

/**
 * Elasticsearch 检索命中的 chunk。
 */
public record SearchHitChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Long userId,
        Long parentChunkId,
        String chunkType,
        Integer chunkIndex,
        String documentTitle,
        String content,
        String metadataJson,
        double score
) {
}
