package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.canal")
public record CanalProperties(
        boolean enabled,
        String host,
        int port,
        String destination,
        String username,
        String password,
        String subscribeRegex,
        int batchSize,
        long emptySleepMillis
) {
}
