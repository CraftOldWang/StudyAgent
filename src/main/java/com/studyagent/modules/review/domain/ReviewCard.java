package com.studyagent.modules.review.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 复习卡实体，保存卡面内容、来源信息和 FSRS 调度字段。
 */
@Getter
@Setter
@TableName("review_cards")
public class ReviewCard {
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Long sessionId;
    private String front;
    private String back;
    private String tagsJson;
    private Long sourceMessageId;
    private String sourceChunkIdsJson;
    private String status;
    private String cardState;
    private LocalDateTime dueAt;
    private LocalDateTime lastReviewedAt;
    private Double stability;
    private Double difficulty;
    private Integer elapsedDays;
    private Integer scheduledDays;
    private Integer reps;
    private Integer lapses;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
