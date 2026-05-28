package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话消息实体，完整保留用户、助手、工具和摘要相关消息。
 */
@Getter
@Setter
@TableName("chat_messages")
public class ChatMessage {
    private Long id;
    private Long sessionId;
    private Long userId;
    private String role;
    private String messageType;
    private String content;
    private String toolName;
    private String toolCallId;
    private String metadataJson;
    private LocalDateTime createdAt;
}
