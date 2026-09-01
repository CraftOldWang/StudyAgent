package com.studyagent.modules.storage.interfaces;

/**
 * 文档处理链路某一种模式的耗时明细。
 */
public record PerformancePipelineStageResponse(
        Long fileId,
        Long documentId,
        long uploadMillis,
        long databaseMillis,
        long messageMillis,
        long processingMillis,
        long responseMillis,
        long indexedMillis,
        String parseStatus,
        String indexStatus,
        int childChunkCount,
        String errorMessage
) {
}
