package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 阶段执行记录，保存每个阶段的输入、输出、状态和错误信息。
 */
@Getter
@Setter
@TableName("agent_step_records")
public class AgentStepRecord {
    private Long id;
    private Long agentRunId;
    private String stage;
    private String status;
    private String inputJson;
    private String outputJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
