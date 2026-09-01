package com.studyagent.modules.storage.interfaces;

/**
 * 前端直连分片性能测试单个 part 上传响应。
 */
public record PerformanceMultipartPartResponse(
        int partNumber,
        String eTag,
        long uploadMillis
) {
}
