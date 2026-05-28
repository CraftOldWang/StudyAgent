package com.studyagent.modules.learning.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 学习会话中的知识点 Todo。
 *
 * <p>PLAN 阶段把用户的大目标拆成多个可执行知识点；后续 Agent 每轮只处理一个 Todo。
 * 这样做的原因是学习过程天然需要“一个知识点讲清楚再进入下一个”，而不是把整套主题塞进
 * 一次模型调用里导致上下文失控。</p>
 */
@Getter
@Setter
@TableName("learning_todos")
public class LearningTodo {
    private Long id;
    private Long sessionId;
    private Long userId;
    private String title;
    private String description;
    private String status;
    private Integer orderIndex;
    private String roundSummary;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
