package com.studyagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.learning.planning.reasoning")
public record LearningPlanningReasoningProperties(@DefaultValue("true") boolean enabled,
        @DefaultValue("16000") int maxTokens, @DefaultValue("high") String effort) {
    public LearningPlanningReasoningProperties {
        if (maxTokens < 1000 || !java.util.Set.of("low", "high", "max").contains(effort)) {
            throw new IllegalArgumentException("Invalid planning reasoning budget or effort");
        }
    }
}
