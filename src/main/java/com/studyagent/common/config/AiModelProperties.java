package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.ai")
public record AiModelProperties(
        Embedding embedding,
        Chat chat
) {

    public record Embedding(
            String provider,
            String model,
            Integer dimensions,
            String apiKey,
            String baseUrl,
            String textType
    ) {
    }

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
