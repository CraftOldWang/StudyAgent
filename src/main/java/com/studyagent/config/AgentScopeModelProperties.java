package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.agentscope.model")
public record AgentScopeModelProperties(
        String primaryModelId,
        String fallbackModelId,
        int maxRetries,
        Provider dashscope,
        Provider deepseek
) {

    public record Provider(String apiKey, String baseUrl) {
    }
}
