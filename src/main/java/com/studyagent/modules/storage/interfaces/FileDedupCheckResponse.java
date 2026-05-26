package com.studyagent.modules.storage.interfaces;

public record FileDedupCheckResponse(
        boolean duplicated,
        Long fileId,
        String status
) {
}
