package com.studyagent.modules.knowledge.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档实体，关联上传文件并记录解析、切块和索引状态。
 */
@Getter
@Setter
@TableName("documents")
public class Document {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long fileId;
    private String title;
    private String sourceType;
    private String parseStatus;
    private String indexStatus;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
