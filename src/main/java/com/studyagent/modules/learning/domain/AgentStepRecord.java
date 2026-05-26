package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

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
