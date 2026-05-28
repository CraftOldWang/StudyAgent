package com.studyagent.modules.review.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 复习记录实体，保存一次复习前后的调度状态变化。
 */
@Getter
@Setter
@TableName("review_records")
public class ReviewRecord {
    private Long id;
    private Long cardId;
    private Long userId;
    private String rating;
    private LocalDateTime reviewedAt;
    private Integer elapsedDays;
    private Integer scheduledDaysBefore;
    private Integer scheduledDaysAfter;
    private Double stabilityBefore;
    private Double stabilityAfter;
    private Double difficultyBefore;
    private Double difficultyAfter;
    private String stateBefore;
    private String stateAfter;
    private LocalDateTime dueAtBefore;
    private LocalDateTime dueAtAfter;
    private LocalDateTime createdAt;
}
