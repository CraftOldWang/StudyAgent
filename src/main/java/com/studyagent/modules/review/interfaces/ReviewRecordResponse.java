package com.studyagent.modules.review.interfaces;

import com.studyagent.modules.review.domain.ReviewRecord;
import java.time.LocalDateTime;

/**
 * 复习记录响应，展示本次复习前后的调度状态变化。
 */
public record ReviewRecordResponse(
        Long id,
        Long cardId,
        String rating,
        LocalDateTime reviewedAt,
        Integer scheduledDaysBefore,
        Integer scheduledDaysAfter,
        Double stabilityBefore,
        Double stabilityAfter,
        Double difficultyBefore,
        Double difficultyAfter,
        String stateBefore,
        String stateAfter,
        LocalDateTime dueAtBefore,
        LocalDateTime dueAtAfter
) {

    /**
     * 将复习记录实体转换为接口响应。
     */
    public static ReviewRecordResponse from(ReviewRecord record) {
        return new ReviewRecordResponse(
                record.getId(),
                record.getCardId(),
                record.getRating(),
                record.getReviewedAt(),
                record.getScheduledDaysBefore(),
                record.getScheduledDaysAfter(),
                record.getStabilityBefore(),
                record.getStabilityAfter(),
                record.getDifficultyBefore(),
                record.getDifficultyAfter(),
                record.getStateBefore(),
                record.getStateAfter(),
                record.getDueAtBefore(),
                record.getDueAtAfter()
        );
    }
}
