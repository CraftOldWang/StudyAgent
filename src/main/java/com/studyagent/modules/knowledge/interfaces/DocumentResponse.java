package com.studyagent.modules.knowledge.interfaces;

import com.studyagent.modules.knowledge.domain.Document;
import java.time.LocalDateTime;

public record DocumentResponse(
        Long id,
        Long knowledgeBaseId,
        Long fileId,
        String title,
        String sourceType,
        String parseStatus,
        String indexStatus,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFileId(),
                document.getTitle(),
                document.getSourceType(),
                document.getParseStatus(),
                document.getIndexStatus(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
