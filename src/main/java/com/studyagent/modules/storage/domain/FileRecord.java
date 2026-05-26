package com.studyagent.modules.storage.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

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
