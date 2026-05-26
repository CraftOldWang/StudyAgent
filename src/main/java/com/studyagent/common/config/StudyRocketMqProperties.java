package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.rocketmq")
public record StudyRocketMqProperties(
        String documentTopic,
        String documentConsumerGroup
) {
}
