package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 运行记录，表示一次状态化学习工作流的整体执行状态。
 */
@Getter
@Setter
@TableName("agent_runs")
public class AgentRun {
    private Long id;
    private Long sessionId;
    private Long userId;
    private String status;
    private String currentStage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
