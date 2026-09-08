package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_turns")
public class LearningTurn {
    @TableId
    private Long id;
    private Long userId;
    private Long sessionId;
    private Long knowledgePointId;
    private String requestId;
    private String inputHash;
    private String userMessage;
    private String assistantMessage;
    private String artifactJson;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String contextDeltaJson;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String preparedContextJson;
    private String status;
    private String phase;
    private String errorMessage;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String processingToken;
    private LocalDateTime leaseUntil;
    private String traceId;
    private Integer attemptCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
