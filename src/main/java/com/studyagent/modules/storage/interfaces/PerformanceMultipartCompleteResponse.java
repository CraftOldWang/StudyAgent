package com.studyagent.modules.storage.interfaces;

/**
 * 前端直连分片性能测试完成响应。
 */
public record PerformanceMultipartCompleteResponse(
        String filename,
        String contentType,
        long fileSize,
        int chunkSizeBytes,
        int totalChunks,
        int chunkConcurrency,
        PerformanceUploadStageResponse multipart
) {
}
