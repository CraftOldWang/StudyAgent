package com.studyagent.ingest.sync;

/**
 * 文档索引消息体，RocketMQ 消费端据此启动文档处理链路。
 */
public record DocumentIndexMessage(
        Long documentId
) {
}
