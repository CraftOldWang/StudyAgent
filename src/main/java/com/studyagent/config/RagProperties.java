package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        int rrfK
) {
}
