package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("embedding_artifacts")
public class EmbeddingArtifact {
    @TableId
    private Long id;
    private Long userId;
    private String contentHash;
    private String model;
    private Integer dimensions;
    private String vectorJson;
    private LocalDateTime createdAt;
}
