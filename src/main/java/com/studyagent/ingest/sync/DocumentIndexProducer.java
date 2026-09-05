package com.studyagent.ingest.sync;

import com.studyagent.config.StudyRocketMqProperties;
import com.studyagent.ingest.sync.DocumentIndexMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 文档索引消息生产者，负责在文件和文档记录提交后发送异步处理消息。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentIndexProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final StudyRocketMqProperties properties;

    /**
     * 发送文档处理消息；若当前存在事务，则等事务提交后再发送。
     */
    public void send(Long documentId, Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            log.info(
                    "检测到当前事务未提交，RocketMQ 文档索引消息将在 afterCommit 发送: topic={}, documentId={}",
                    properties.documentTopic(),
                    documentId
            );
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNow(documentId, userId);
                }
            });
            return;
        }
        sendNow(documentId, userId);
    }

    /**
     * 立即发送 RocketMQ 消息。
     */
    private void sendNow(Long documentId, Long userId) {
        long startedAt = System.nanoTime();
        log.info("发送 RocketMQ 文档索引消息: topic={}, documentId={}", properties.documentTopic(), documentId);
        rocketMQTemplate.convertAndSend(properties.documentTopic(), new DocumentIndexMessage(documentId, userId));
        log.info(
                "RocketMQ 文档索引消息发送完成: topic={}, documentId={}, messageMillis={}",
                properties.documentTopic(),
                documentId,
                java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        );
    }
}
