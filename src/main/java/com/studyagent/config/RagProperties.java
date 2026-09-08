package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 检索、切块和父子检索配置。
 *
 * <p>chunkSize/chunkOverlap 表示用于召回的子 chunk 粒度；parentChunkSize/parentChunkOverlap
 * 表示用于回填上下文的父 chunk 粒度。这样可以让 ES 精确命中小片段，同时把更完整的父段落交给模型。</p>
 */
@ConfigurationProperties(prefix = "study-agent.rag")
public record RagProperties(
        int topK,
        int chunkSize,
        int chunkOverlap,
        int parentChunkSize,
        int parentChunkOverlap,
        int bm25CandidateSize,
        int vectorCandidateSize,
        int rrfK,
        @DefaultValue("STRUCTURED") ChunkStrategy chunkStrategy,
        @DefaultValue("4096") int contextMaxTokens
) {
    public enum ChunkStrategy { STRUCTURED, FIXED }

    public RagProperties {
        if (chunkSize <= 0 || chunkOverlap < 0 || chunkOverlap >= chunkSize
                || parentChunkSize < chunkSize || parentChunkOverlap < 0 || parentChunkOverlap >= parentChunkSize) {
            throw new IllegalArgumentException("Invalid child/parent token window configuration");
        }
        if (chunkStrategy == null || (chunkStrategy == ChunkStrategy.STRUCTURED && parentChunkOverlap != 0)) {
            throw new IllegalArgumentException("Structured parents require zero overlap");
        }
        if (topK <= 0 || topK > 20 || bm25CandidateSize <= 0 || vectorCandidateSize <= 0 || rrfK <= 0
                || contextMaxTokens < parentChunkSize) {
            throw new IllegalArgumentException("Invalid retrieval limits or context budget smaller than one parent");
        }
    }
}
