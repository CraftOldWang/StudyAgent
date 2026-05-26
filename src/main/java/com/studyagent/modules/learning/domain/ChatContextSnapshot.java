package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("chat_context_snapshots")
public class ChatContextSnapshot {
    private Long id;
    private Long sessionId;
    private Long coveredMessageId;
    private String summaryContent;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}
