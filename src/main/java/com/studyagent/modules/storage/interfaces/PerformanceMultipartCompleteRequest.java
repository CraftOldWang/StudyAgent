package com.studyagent.modules.storage.interfaces;

import java.util.List;

/**
 * 前端直连分片性能测试完成请求。
 */
public record PerformanceMultipartCompleteRequest(
        Long knowledgeBaseId,
        String filename,
        String contentType,
        long fileSize,
        String objectKey,
        String uploadId,
        int chunkSizeBytes,
        int totalChunks,
        int chunkConcurrency,
        long browserUploadMillis,
        boolean triggerIndex,
        List<Part> parts
) {

    public record Part(
            int partNumber,
            String eTag
    ) {
    }
}
