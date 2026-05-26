package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.rag")
public record RagProperties(
        int topK,
        int chunkSize,
        int chunkOverlap
) {
}
