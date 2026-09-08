package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_plan_stages")
public class LearningPlanStage {
    @TableId
    private Long id;
    private Long userId;
    private Long runId;
    private String stageKey;
    private String inputHash;
    private String inputJson;
    private String outputJson;
    private String rawOutput;
    private String usageJson;
    private String status;
    private String errorMessage;
    private String traceId;
    private Integer attemptCount;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long elapsedMillis;
}
