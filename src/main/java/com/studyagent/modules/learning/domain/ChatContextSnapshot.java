package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话上下文压缩快照，记录压缩摘要覆盖到的最后一条消息 ID。
 */
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
