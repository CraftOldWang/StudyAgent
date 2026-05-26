package com.studyagent.infrastructure.mq;

import com.studyagent.common.config.StudyRocketMqProperties;
import com.studyagent.modules.knowledge.application.DocumentIndexMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentIndexProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final StudyRocketMqProperties properties;

    public void send(Long documentId) {
        rocketMQTemplate.convertAndSend(properties.documentTopic(), new DocumentIndexMessage(documentId));
    }
}
