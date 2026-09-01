package com.studyagent.modules.storage.interfaces;

/**
 * RustFS 上传方式性能对比响应。
 */
public record PerformanceUploadComparisonResponse(
        String filename,
        String contentType,
        long fileSize,
        int chunkSizeBytes,
        int totalChunks,
        int chunkConcurrency,
        boolean triggerIndex,
        String summary,
        PerformanceUploadStageResponse direct,
        PerformanceUploadStageResponse multipart
) {
}
