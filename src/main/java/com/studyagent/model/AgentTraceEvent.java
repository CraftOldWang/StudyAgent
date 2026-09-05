package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_trace_events")
public class AgentTraceEvent {

    @TableId("id")
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("trace_id")
    private String traceId;

    @TableField("session_id")
    private Long sessionId;

    @TableField("sequence_no")
    private Integer sequenceNo;

    @TableField("stage")
    private String stage;

    @TableField("event_type")
    private String eventType;

    @TableField("summary")
    private String summary;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
