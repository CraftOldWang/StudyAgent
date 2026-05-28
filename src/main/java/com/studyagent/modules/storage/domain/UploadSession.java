package com.studyagent.modules.storage.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 分片上传会话实体，保存一次大文件上传的业务状态和完成结果。
 *
 * <p>已上传分片明细保存在 Redis Bitmap 中，本表只保存可恢复的会话元数据和进度摘要。</p>
 */
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
