package com.studyagent.modules.review.domain;

import com.studyagent.common.exception.BusinessException;

public enum ReviewRating {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4);

    private final int value;

    ReviewRating(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static ReviewRating from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("复习评分不能为空");
        }
        try {
            return ReviewRating.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("复习评分必须是 AGAIN、HARD、GOOD 或 EASY");
        }
    }
}
