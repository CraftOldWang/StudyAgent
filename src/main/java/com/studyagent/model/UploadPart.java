package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("upload_parts")
public class UploadPart {
    private Long id;
    private Long userId;
    private Long uploadSessionId;
    private Integer chunkIndex;
    private String etag;
    private Long byteSize;
    private LocalDateTime createdAt;
}
