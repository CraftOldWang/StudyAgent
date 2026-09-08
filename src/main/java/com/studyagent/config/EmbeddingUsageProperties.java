package com.studyagent.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "study-agent.embedding-usage")
public record EmbeddingUsageProperties(@DefaultValue(".eval/embedding-calls.jsonl") Path ledger) {
}
