package com.studyagent.modules.review.domain;

import java.time.Duration;
import java.time.LocalDateTime;

public class FsrsScheduler {

    private static final double[] W = {
            0.4072, 1.1829, 3.1262, 15.4722, 7.2102, 0.5316, 1.0651, 0.0234,
            1.616, 0.1544, 1.0824, 1.9813, 0.0953, 0.2975, 2.2042, 0.2407,
            2.9466, 0.5034, 0.6567
    };
    private static final double REQUEST_RETENTION = 0.9d;
    private static final double DECAY = -0.5d;
    private static final double FACTOR = Math.pow(0.9d, 1.0d / DECAY) - 1.0d;

    public FsrsSchedulingResult schedule(FsrsCardState before, ReviewRating rating, LocalDateTime reviewedAt) {
        int elapsedDays = elapsedDays(before, reviewedAt);
        double retrievability = retrievability(before.stability(), elapsedDays);
        double nextDifficulty = nextDifficulty(before.difficulty(), rating);
        double nextStability = nextStability(before, rating, retrievability);
        CardState nextState = nextState(before.state(), rating);
        int scheduledDays = scheduledDays(nextStability, rating, nextState);
        LocalDateTime dueAt = dueAt(reviewedAt, scheduledDays, rating, nextState);
        int reps = before.reps() + 1;
        int lapses = before.lapses() + (rating == ReviewRating.AGAIN && before.state() == CardState.REVIEW ? 1 : 0);

        FsrsCardState after = new FsrsCardState(
                nextState,
                dueAt,
                reviewedAt,
                round(nextStability),
                round(nextDifficulty),
                elapsedDays,
                scheduledDays,
                reps,
                lapses
        );
        return new FsrsSchedulingResult(before, after, rating);
    }

    public FsrsCardState newCard(LocalDateTime now) {
        return new FsrsCardState(CardState.NEW, now, null, 0.0d, 0.0d, 0, 0, 0, 0);
    }

    private double nextDifficulty(double currentDifficulty, ReviewRating rating) {
        double initial = currentDifficulty <= 0 ? initDifficulty(rating) : currentDifficulty;
        double delta = W[6] * (rating.value() - 3);
        double next = initial - meanReversion(W[4], delta);
        return constrain(next, 1.0d, 10.0d);
    }

    private double nextStability(FsrsCardState before, ReviewRating rating, double retrievability) {
        if (before.state() == CardState.NEW || before.stability() <= 0) {
            return initStability(rating);
        }
        if (rating == ReviewRating.AGAIN) {
            return nextForgetStability(before.difficulty(), before.stability(), retrievability);
        }
        double stability = nextRecallStability(before.difficulty(), before.stability(), retrievability, rating);
        if (rating == ReviewRating.HARD) {
            stability = Math.min(stability, before.stability() * 1.5d);
        }
        return Math.max(stability, before.stability() + 0.01d);
    }

    private double initStability(ReviewRating rating) {
        return switch (rating) {
            case AGAIN -> W[0];
            case HARD -> W[1];
            case GOOD -> W[2];
            case EASY -> W[3];
        };
    }

    private double initDifficulty(ReviewRating rating) {
        return constrain(W[4] - Math.exp((rating.value() - 1) * W[5]) + 1, 1.0d, 10.0d);
    }

    private double meanReversion(double init, double current) {
        return W[7] * init + (1 - W[7]) * current;
    }

    private double nextRecallStability(double difficulty, double stability, double retrievability, ReviewRating rating) {
        double hardPenalty = rating == ReviewRating.HARD ? W[15] : 1.0d;
        double easyBonus = rating == ReviewRating.EASY ? W[16] : 1.0d;
        return stability * (1 + Math.exp(W[8])
                * (11 - difficulty)
                * Math.pow(stability, -W[9])
                * (Math.exp((1 - retrievability) * W[10]) - 1)
                * hardPenalty
                * easyBonus);
    }

    private double nextForgetStability(double difficulty, double stability, double retrievability) {
        double forgetStability = W[11]
                * Math.pow(difficulty, -W[12])
                * (Math.pow(stability + 1, W[13]) - 1)
                * Math.exp((1 - retrievability) * W[14]);
        return Math.min(forgetStability, stability / Math.exp(W[17] * W[18]));
    }

    private double retrievability(double stability, int elapsedDays) {
        if (stability <= 0 || elapsedDays <= 0) {
            return 1.0d;
        }
        return Math.pow(1 + FACTOR * elapsedDays / stability, DECAY);
    }

    private int scheduledDays(double stability, ReviewRating rating, CardState state) {
        if (rating == ReviewRating.AGAIN) {
            return 0;
        }
        if (state == CardState.LEARNING) {
            return rating == ReviewRating.HARD ? 0 : 1;
        }
        int interval = (int) Math.round(stability / FACTOR * (Math.pow(REQUEST_RETENTION, 1.0d / DECAY) - 1));
        if (rating == ReviewRating.HARD) {
            interval = Math.min(interval, 4);
        }
        return Math.max(1, interval);
    }

    private LocalDateTime dueAt(LocalDateTime reviewedAt, int scheduledDays, ReviewRating rating, CardState state) {
        if (rating == ReviewRating.AGAIN) {
            return reviewedAt.plusMinutes(5);
        }
        if (state == CardState.LEARNING && scheduledDays == 0) {
            return reviewedAt.plusMinutes(10);
        }
        return reviewedAt.plusDays(scheduledDays);
    }

    private CardState nextState(CardState state, ReviewRating rating) {
        if (rating == ReviewRating.AGAIN) {
            return state == CardState.REVIEW ? CardState.RELEARNING : CardState.LEARNING;
        }
        return rating == ReviewRating.HARD && state == CardState.NEW ? CardState.LEARNING : CardState.REVIEW;
    }

    private int elapsedDays(FsrsCardState before, LocalDateTime reviewedAt) {
        if (before.lastReviewedAt() == null) {
            return 0;
        }
        long days = Duration.between(before.lastReviewedAt(), reviewedAt).toDays();
        return Math.max(0, Math.toIntExact(days));
    }

    private double constrain(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
