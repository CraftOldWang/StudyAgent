package com.studyagent.modules.tool.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 工具调用审计记录，保存 Agent 每次工具调用的参数、结果和失败原因。
 */
@Getter
@Setter
@TableName("tool_call_records")
public class ToolCallRecord {
    private Long id;
    private Long agentRunId;
    private Long sessionId;
    private Long userId;
    private String toolName;
    private String argumentsJson;
    private String resultSummary;
    private String status;
    private Boolean permissionChecked;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
