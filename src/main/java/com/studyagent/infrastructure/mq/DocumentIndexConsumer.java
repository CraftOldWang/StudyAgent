package com.studyagent.infrastructure.mq;

import com.studyagent.modules.knowledge.application.DocumentIndexMessage;
import com.studyagent.modules.knowledge.application.DocumentProcessingService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${study-agent.rocketmq.document-topic}",
        consumerGroup = "${study-agent.rocketmq.document-consumer-group}"
)
public class DocumentIndexConsumer implements RocketMQListener<DocumentIndexMessage> {

    private final DocumentProcessingService documentProcessingService;

    @Override
    public void onMessage(DocumentIndexMessage message) {
        documentProcessingService.process(message.documentId());
    }
}
