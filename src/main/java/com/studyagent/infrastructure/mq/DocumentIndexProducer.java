package com.studyagent.infrastructure.mq;

import com.studyagent.common.config.StudyRocketMqProperties;
import com.studyagent.modules.knowledge.application.DocumentIndexMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class DocumentIndexProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final StudyRocketMqProperties properties;

    public void send(Long documentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNow(documentId);
                }
            });
            return;
        }
        sendNow(documentId);
    }

    private void sendNow(Long documentId) {
        rocketMQTemplate.convertAndSend(properties.documentTopic(), new DocumentIndexMessage(documentId));
    }
}
