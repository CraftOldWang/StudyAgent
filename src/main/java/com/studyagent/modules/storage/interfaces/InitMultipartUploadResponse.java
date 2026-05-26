package com.studyagent.modules.storage.interfaces;

public record InitMultipartUploadResponse(
        Long uploadSessionId,
        boolean duplicated,
        Long fileId,
        Long documentId,
        String status
) {
}
