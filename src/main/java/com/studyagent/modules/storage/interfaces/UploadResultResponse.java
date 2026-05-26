package com.studyagent.modules.storage.interfaces;

public record UploadResultResponse(
        Long fileId,
        Long documentId,
        String status
) {
}
