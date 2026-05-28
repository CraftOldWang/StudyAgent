package com.studyagent.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目 RocketMQ 业务主题配置。
 */
@ConfigurationProperties(prefix = "study-agent.rocketmq")
public record StudyRocketMqProperties(
        String documentTopic,
        String documentConsumerGroup
) {
}
