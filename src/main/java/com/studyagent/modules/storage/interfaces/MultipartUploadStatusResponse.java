package com.studyagent.modules.storage.interfaces;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分片上传状态响应，包含已上传和缺失的分片列表，支持客户端断点续传。
 */
public record MultipartUploadStatusResponse(
        Long uploadSessionId,
        Long knowledgeBaseId,
        String filename,
        String fileHash,
        long fileSize,
        int chunkSize,
        int totalChunks,
        int uploadedChunks,
        List<Integer> uploadedChunkIndexes,
        List<Integer> missingChunkIndexes,
        String status,
        Long fileId,
        Long documentId,
        LocalDateTime expiresAt
) {
}
