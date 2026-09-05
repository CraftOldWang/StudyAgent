package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("quizzes")
public class Quiz {

    @TableId("id")
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private Long sessionId;

    @TableField("knowledge_point_id")
    private Long knowledgePointId;

    @TableField("questions_json")
    private String questionsJson;

    @TableField("answers_json")
    private String answersJson;

    @TableField("score")
    private Integer score;

    @TableField("feedback_json")
    private String feedbackJson;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("answered_at")
    private LocalDateTime answeredAt;
}
