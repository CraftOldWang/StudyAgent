package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elasticsearch 检索和向量索引配置。
 */
@ConfigurationProperties(prefix = "study-agent.elasticsearch")
public record ElasticsearchProperties(
        String endpoint,
        String physicalIndex,
        String readAlias,
        String writeAlias,
        int vectorDimensions
) {
}
