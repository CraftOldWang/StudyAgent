package com.studyagent.infrastructure.mq;

import com.studyagent.common.config.StudyRocketMqProperties;
import com.studyagent.modules.knowledge.application.DocumentIndexMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 文档索引消息生产者，负责在文件和文档记录提交后发送异步处理消息。
 */
@Component
@RequiredArgsConstructor
public class DocumentIndexProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final StudyRocketMqProperties properties;

    /**
     * 发送文档处理消息；若当前存在事务，则等事务提交后再发送。
     */
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

    /**
     * 立即发送 RocketMQ 消息。
     */
    private void sendNow(Long documentId) {
        rocketMQTemplate.convertAndSend(properties.documentTopic(), new DocumentIndexMessage(documentId));
    }
}
