package com.studyagent.rag.retrieval;

/**
 * 单策略检索的最小统一命中，供后续融合和父块聚合保留召回证据。
 */
public record RetrievalHit(
        String chunkId,
        String parentChunkId,
        String content,
        double score,
        RetrievalStrategy strategy
) {
}
