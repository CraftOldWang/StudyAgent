package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型配置属性，包含 embedding 和 chat provider 参数。
 */
@ConfigurationProperties(prefix = "study-agent.ai")
public record AiModelProperties(
        Embedding embedding,
        Chat chat
) {

    /**
     * Embedding 模型配置。
     */
    public record Embedding(
            String provider,
            String model,
            Integer dimensions,
            String apiKey,
            String baseUrl,
            String textType
    ) {
    }

    /**
     * Chat 模型配置。
     */
    public record Chat(
            String provider,
            String model,
            String apiKey,
            String baseUrl,
            Double temperature,
            Integer maxTokens
    ) {
    }
}
