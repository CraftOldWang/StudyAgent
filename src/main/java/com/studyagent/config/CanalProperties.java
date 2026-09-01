package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Canal binlog 监听配置。
 */
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
