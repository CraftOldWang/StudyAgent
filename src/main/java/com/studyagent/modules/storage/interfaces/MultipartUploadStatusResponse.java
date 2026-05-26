package com.studyagent.modules.storage.interfaces;

import java.time.LocalDateTime;
import java.util.List;

public record MultipartUploadStatusResponse(
        Long uploadSessionId,
        Long knowledgeBaseId,
        String filename,
        String md5,
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
