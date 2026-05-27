package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("quiz_questions")
public class QuizQuestion {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long sessionId;
    private Long agentRunId;
    private String questionType;
    private String questionText;
    private String correctAnswer;
    private String explanation;
    private String optionsJson;
    private String sourceChunkIdsJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
