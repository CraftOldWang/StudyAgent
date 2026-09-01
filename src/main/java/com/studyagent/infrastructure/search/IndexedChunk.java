package com.studyagent.infrastructure.search;

/**
 * 待写入 Elasticsearch 的 chunk 索引文档。
 */
public record IndexedChunk(
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
        float[] embedding
) {
}
