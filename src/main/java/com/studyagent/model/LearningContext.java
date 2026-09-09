package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("learning_contexts")
public class LearningContext {
    @TableId
    private Long sessionId;
    private Long userId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String agentStateJson;
    private String compressionStrategy;
    private Long pendingPointId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String pendingSummaryInput;
    private String pendingSummaryText;
    private String pendingSummaryStatus;
    private String pendingSummaryError;
    private LocalDateTime updatedAt;
}
