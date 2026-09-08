package com.studyagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.ingest.pipeline")
public record DocumentPipelineProperties(
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("30s") Duration recoveryInterval,
        @DefaultValue("100") int recoveryBatchSize
) {
    public DocumentPipelineProperties {
        if (leaseDuration.isNegative() || leaseDuration.isZero()
                || recoveryInterval.isNegative() || recoveryInterval.isZero() || recoveryBatchSize <= 0) {
            throw new IllegalArgumentException("Pipeline lease, recovery interval and batch size must be positive");
        }
    }
}
