package com.studyagent.modules.storage.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("upload_sessions")
public class UploadSession {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private String fileMd5;
    private String filename;
    private String contentType;
    private Integer chunkSize;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long fileSize;
    private Long completedFileId;
    private Long completedDocumentId;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
