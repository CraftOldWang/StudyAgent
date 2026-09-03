package com.studyagent.modules.knowledge.interfaces;

import com.studyagent.model.Document;
import java.time.LocalDateTime;

/**
 * 文档列表响应，暴露文档处理状态给前端展示。
 */
public record DocumentResponse(
        Long id,
        Long knowledgeBaseId,
        Long fileRecordId,
        String title,
        String contentType,
        String pipelineStatus,
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
                document.getFileRecordId(),
                document.getTitle(),
                document.getContentType(),
                document.getPipelineStatus(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
