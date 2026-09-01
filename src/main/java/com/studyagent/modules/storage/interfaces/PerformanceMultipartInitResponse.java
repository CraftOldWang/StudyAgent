package com.studyagent.modules.storage.interfaces;

/**
 * 前端直连分片性能测试初始化响应。
 */
public record PerformanceMultipartInitResponse(
        String uploadId,
        String objectKey,
        int chunkSizeBytes,
        int totalChunks
) {
}
