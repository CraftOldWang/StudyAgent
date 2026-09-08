package com.studyagent.config;

import com.studyagent.ingest.pipeline.DocumentRecoveryJob;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DocumentPipelineProperties.class)
@RequiredArgsConstructor
public class DocumentRecoveryConfiguration implements SchedulingConfigurer {
    private final DocumentRecoveryJob job;
    private final DocumentPipelineProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(job::requeueExpired, properties.recoveryInterval().toMillis());
    }
}
