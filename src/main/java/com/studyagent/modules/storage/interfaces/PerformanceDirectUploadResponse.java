package com.studyagent.modules.storage.interfaces;

/**
 * 前端单请求直传性能测试响应。
 */
public record PerformanceDirectUploadResponse(
        String filename,
        String contentType,
        long fileSize,
        PerformanceUploadStageResponse direct
) {
}
