package com.studyagent.modules.storage.interfaces;

/**
 * 同步处理与 RocketMQ 解耦处理的性能对比响应。
 */
public record PerformancePipelineComparisonResponse(
        String filename,
        String contentType,
        long fileSize,
        long waitTimeoutMillis,
        PerformancePipelineStageResponse synchronous,
        PerformancePipelineStageResponse rocketMq
) {
}
