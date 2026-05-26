package com.studyagent.modules.review.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public record SubmitReviewRequest(
        @NotBlank String rating,
        LocalDateTime reviewedAt
) {
}
