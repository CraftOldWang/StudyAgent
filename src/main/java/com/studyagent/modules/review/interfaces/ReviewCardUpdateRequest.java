package com.studyagent.modules.review.interfaces;

import java.util.List;

public record ReviewCardUpdateRequest(
        String front,
        String back,
        List<String> tags,
        String status
) {
}
