package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 测验作答记录实体，保存用户答案、评分和反馈。
 */
@Getter
@Setter
@TableName("quiz_answers")
public class QuizAnswer {
    private Long id;
    private Long questionId;
    private Long userId;
    private Long sessionId;
    private String userAnswer;
    private String evaluation;
    private Boolean correct;
    private Integer score;
    private LocalDateTime answeredAt;
    private LocalDateTime createdAt;
}
