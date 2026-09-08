package com.studyagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("study-agent.upload")
public record UploadProperties(@DefaultValue("1d") Duration sessionTtl,
                               @DefaultValue("1073741824") long maxFileBytes,
                               @DefaultValue("67108864") int maxChunkBytes) {}
