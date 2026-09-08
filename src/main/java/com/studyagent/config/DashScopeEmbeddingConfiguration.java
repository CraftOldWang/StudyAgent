package com.studyagent.config;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DashScopeEmbeddingConfiguration {

    @Bean
    public TextEmbedding dashScopeTextEmbedding(AiModelProperties properties) {
        return new TextEmbedding(nativeBaseUrl(properties.embedding().baseUrl()));
    }

    static String nativeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding base URL must not be blank");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        // The supplied workspace URL uses the OpenAI route; this SDK uses the DashScope route.
        String compatiblePath = "/compatible-mode/v1";
        return normalized.endsWith(compatiblePath)
                ? normalized.substring(0, normalized.length() - compatiblePath.length()) + "/api/v1"
                : normalized;
    }
}
