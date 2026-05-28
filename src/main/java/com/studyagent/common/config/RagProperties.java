package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索、切块和父子上下文扩展配置。
 */
@ConfigurationProperties(prefix = "study-agent.rag")
public record RagProperties(
        int topK,
        int chunkSize,
        int chunkOverlap,
        int bm25CandidateSize,
        int vectorCandidateSize,
        int rrfK,
        int parentBefore,
        int parentAfter
) {
}
