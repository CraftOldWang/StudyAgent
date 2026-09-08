package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_plan_runs")
public class LearningPlanRun {
    @TableId
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private String learningGoal;
    private String inputJson;
    private String status;
    private String errorMessage;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String processingToken;
    private LocalDateTime leaseUntil;
    private Long sessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
