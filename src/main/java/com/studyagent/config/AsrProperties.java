package com.studyagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("study-agent.asr")
public record AsrProperties(@DefaultValue("http://127.0.0.1:8767/transcribe") String endpoint,
                            @DefaultValue("2h") Duration timeout,
                            @DefaultValue("fw-small-536b066-v1") String processorVersion) {}
