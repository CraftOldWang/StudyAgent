package com.studyagent.modules.storage.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文件实体，记录对象存储中的物理文件及其去重哈希。
 *
 * <p>多个知识库文档可以复用同一条文件记录，实现“文件只存一份、文档按知识库创建”。</p>
 */
@Getter
@Setter
@TableName("file_records")
public class FileRecord {
    private Long id;
    private Long userId;
    private String md5;
    private String sha256;
    private String bucket;
    private String objectKey;
    private String filename;
    private String contentType;
    private Long size;
    private String storageProvider;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
