package com.studyagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("study-agent.anki")
public record AnkiProperties(
        @DefaultValue("http://127.0.0.1:8765") String endpoint,
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("StudyPilot v1") String modelName,
        @DefaultValue("StudyPilot") String deckPrefix) {}
