package com.studyagent.modules.review.interfaces;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 提交复习结果请求，reviewedAt 为空时使用服务器当前时间。
 */
public record SubmitReviewRequest(
        @NotBlank String rating,
        LocalDateTime reviewedAt
) {
}
