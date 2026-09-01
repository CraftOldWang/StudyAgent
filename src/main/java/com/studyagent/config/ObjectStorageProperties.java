package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 兼容对象存储配置。
 */
@ConfigurationProperties(prefix = "study-agent.object-storage")
public record ObjectStorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        boolean pathStyleAccess
) {
}
