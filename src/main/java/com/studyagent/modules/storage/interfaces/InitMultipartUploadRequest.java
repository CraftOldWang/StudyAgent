package com.studyagent.modules.storage.interfaces;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InitMultipartUploadRequest(
        @NotNull Long knowledgeBaseId,
        @NotBlank String filename,
        String contentType,
        @NotBlank String md5,
        String sha256,
        @Min(1) long fileSize,
        @Min(1) int chunkSize,
        @Min(1) int totalChunks
) {
}
