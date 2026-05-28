package com.studyagent.modules.review.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 创建复习卡请求，来源字段用于追溯到会话消息和知识库 chunk。
 */
public record ReviewCardCreateRequest(
        Long knowledgeBaseId,
        Long documentId,
        Long sessionId,
        @NotBlank String front,
        @NotBlank String back,
        List<String> tags,
        Long sourceMessageId,
        List<Long> sourceChunkIds
) {
}
