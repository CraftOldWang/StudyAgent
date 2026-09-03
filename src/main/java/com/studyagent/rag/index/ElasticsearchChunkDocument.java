package com.studyagent.rag.index;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * 与 chunks-v1 mapping 一一对应的索引文档。
 */
public record ElasticsearchChunkDocument(
        @JsonProperty("user_id") String userId,
        @JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("document_id") String documentId,
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("parent_chunk_id") String parentChunkId,
        @JsonProperty("chunk_type") String chunkType,
        @JsonProperty("chunk_index") Integer chunkIndex,
        String content,
        @JsonProperty("content_hash") String contentHash,
        float[] embedding,
        @JsonProperty("chunker_version") String chunkerVersion,
        @JsonProperty("embedding_model") String embeddingModel,
        @JsonProperty("created_at") LocalDateTime createdAt
) {
}
