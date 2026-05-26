package com.studyagent.modules.review.domain;

import java.time.LocalDateTime;

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
