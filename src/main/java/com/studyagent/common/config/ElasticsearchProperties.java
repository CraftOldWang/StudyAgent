package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.elasticsearch")
public record ElasticsearchProperties(
        String endpoint,
        String chunkIndex,
        int vectorDimensions
) {
}
