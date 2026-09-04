package com.studyagent.ingest.sync;

import com.studyagent.ingest.pipeline.DocumentPipeline;
import com.studyagent.ingest.sync.DocumentIndexMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 文档索引消息消费者，收到消息后触发文档处理链路。
 */
@Component
@Slf4j
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${study-agent.rocketmq.document-topic}",
        consumerGroup = "${study-agent.rocketmq.document-consumer-group}"
)
public class DocumentIndexConsumer implements RocketMQListener<DocumentIndexMessage> {

    private final DocumentPipeline documentPipeline;

    /**
     * 消费文档处理消息。
     */
    @Override
    public void onMessage(DocumentIndexMessage message) {
        long startedAt = System.nanoTime();
        Long documentId = message == null ? null : message.documentId();
        log.info("收到 RocketMQ 文档索引消息: documentId={}", documentId);
        try {
            documentPipeline.process(documentId);
            log.info(
                    "RocketMQ 文档索引消息消费完成: documentId={}, consumeMillis={}",
                    documentId,
                    java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            );
        } catch (RuntimeException ex) {
            log.error(
                    "RocketMQ 文档索引消息消费失败，将异常抛回 RocketMQ 触发重试或失败状态: documentId={}, consumeMillis={}",
                    documentId,
                    java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    ex
            );
            throw ex;
        }
    }
}
