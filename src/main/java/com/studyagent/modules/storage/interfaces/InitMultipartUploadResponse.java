package com.studyagent.modules.storage.interfaces;

/**
 * 初始化分片上传响应，兼容秒传、恢复已有会话和创建新会话三种情况。
 */
public record InitMultipartUploadResponse(
        Long uploadSessionId,
        boolean duplicated,
        Long fileId,
        Long documentId,
        String status,
        int uploadedChunks,
        int totalChunks
) {
}
