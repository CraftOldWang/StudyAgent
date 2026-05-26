package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("chat_sessions")
public class ChatSession {
    private Long id;
    private Long userId;
    private String title;
    private String mode;
    private String status;
    private String knowledgeBaseScopeJson;
    private Boolean webSearchEnabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
