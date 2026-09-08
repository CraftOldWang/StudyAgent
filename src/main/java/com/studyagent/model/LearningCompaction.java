package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_compactions")
public class LearningCompaction {
    @TableId
    private Long id;
    private Long userId;
    private Long sessionId;
    private Long turnId;
    private Long knowledgePointId;
    private String kind;
    private String inputHash;
    private String summaryText;
    private String traceId;
    private LocalDateTime createdAt;
}
