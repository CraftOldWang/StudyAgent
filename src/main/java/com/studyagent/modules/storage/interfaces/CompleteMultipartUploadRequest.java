package com.studyagent.modules.storage.interfaces;

import jakarta.validation.constraints.NotNull;

public record CompleteMultipartUploadRequest(
        @NotNull Long uploadSessionId,
        @NotNull Long knowledgeBaseId
) {
}
