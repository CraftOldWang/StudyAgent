package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.learning.planning")
public record LearningPlanningProperties(
        @DefaultValue("4800") int batchTokens,
        @DefaultValue("28000") int inputTokens,
        @DefaultValue("6000") int outputTokens,
        @DefaultValue("600") int leaseSeconds) {
    public LearningPlanningProperties {
        if (batchTokens < 2400 || inputTokens < batchTokens || outputTokens < 1000 || leaseSeconds < 60) {
            throw new IllegalArgumentException("Invalid planning token budget or lease");
        }
    }
}
