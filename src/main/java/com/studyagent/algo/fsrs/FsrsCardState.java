package com.studyagent.algo.fsrs;

import java.time.LocalDateTime;

/**
 * FSRS 卡片调度状态快照。
 */
public record FsrsCardState(
        CardState state,
        LocalDateTime dueAt,
        LocalDateTime lastReviewedAt,
        double stability,
        double difficulty,
        int elapsedDays,
        int scheduledDays,
        int reps,
        int lapses
) {
}
