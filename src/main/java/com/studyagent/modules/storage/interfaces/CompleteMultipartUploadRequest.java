package com.studyagent.modules.storage.interfaces;

import jakarta.validation.constraints.NotNull;

/**
 * 完成分片上传的请求参数，用于绑定上传会话和目标知识库。
 */
public record CompleteMultipartUploadRequest(
        @NotNull Long uploadSessionId,
        @NotNull Long knowledgeBaseId
) {
}
