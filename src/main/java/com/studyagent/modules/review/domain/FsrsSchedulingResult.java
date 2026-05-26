package com.studyagent.modules.review.domain;

public record FsrsSchedulingResult(
        FsrsCardState before,
        FsrsCardState after,
        ReviewRating rating
) {
}
