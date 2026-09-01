package com.studyagent.modules.storage.interfaces;

/**
 * 单个上传模式的耗时明细。
 */
public record PerformanceUploadStageResponse(
        String mode,
        Long fileId,
        Long documentId,
        String objectKey,
        String uploadId,
        long uploadMillis,
        long mergeMillis,
        long databaseMillis,
        long totalMillis
) {
}
