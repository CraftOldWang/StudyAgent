package com.studyagent.modules.review.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

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
