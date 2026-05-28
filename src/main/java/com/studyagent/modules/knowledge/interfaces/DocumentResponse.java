package com.studyagent.modules.knowledge.interfaces;

import com.studyagent.modules.knowledge.domain.Document;
import java.time.LocalDateTime;

/**
 * 文档列表响应，暴露文档处理状态给前端展示。
 */
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

    /**
     * 将持久化文档实体转换为接口响应。
     */
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
