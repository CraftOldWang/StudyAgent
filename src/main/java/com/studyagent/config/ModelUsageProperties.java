package com.studyagent.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.model-usage")
public record ModelUsageProperties(
        @DefaultValue(".eval/model-calls.jsonl") Path ledger,
        @DefaultValue("2000") int maxCalls) {
    public ModelUsageProperties {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("Model call limit must be positive");
        }
    }
}
