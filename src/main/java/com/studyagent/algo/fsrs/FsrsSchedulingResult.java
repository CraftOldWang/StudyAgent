package com.studyagent.algo.fsrs;

/**
 * 一次 FSRS 调度的前后状态和用户评分。
 */
public record FsrsSchedulingResult(
        FsrsCardState before,
        FsrsCardState after,
        ReviewRating rating
) {
}
