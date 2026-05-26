package com.studyagent.modules.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class FsrsSchedulerTest {

    private final FsrsScheduler scheduler = new FsrsScheduler();

    @Test
    void newCardGoodRatingShouldGraduateToReview() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 26, 10, 0);
        FsrsCardState card = scheduler.newCard(now);

        FsrsSchedulingResult result = scheduler.schedule(card, ReviewRating.GOOD, now);

        assertThat(result.after().state()).isEqualTo(CardState.REVIEW);
        assertThat(result.after().stability()).isGreaterThan(0);
        assertThat(result.after().difficulty()).isBetween(1.0, 10.0);
        assertThat(result.after().scheduledDays()).isGreaterThanOrEqualTo(1);
        assertThat(result.after().dueAt()).isAfter(now);
        assertThat(result.after().reps()).isEqualTo(1);
    }

    @Test
    void reviewAgainShouldEnterRelearningAndCountLapse() {
        LocalDateTime firstReview = LocalDateTime.of(2026, 5, 20, 10, 0);
        FsrsCardState card = new FsrsCardState(
                CardState.REVIEW,
                firstReview.plusDays(5),
                firstReview,
                5.0,
                5.0,
                0,
                5,
                3,
                0
        );

        FsrsSchedulingResult result = scheduler.schedule(card, ReviewRating.AGAIN, firstReview.plusDays(6));

        assertThat(result.after().state()).isEqualTo(CardState.RELEARNING);
        assertThat(result.after().lapses()).isEqualTo(1);
        assertThat(result.after().scheduledDays()).isZero();
        assertThat(result.after().dueAt()).isEqualTo(firstReview.plusDays(6).plusMinutes(5));
    }
}
