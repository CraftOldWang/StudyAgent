package com.studyagent.modules.review.interfaces;

import java.util.List;

/**
 * 更新复习卡请求，字段为空表示不修改。
 */
public record ReviewCardUpdateRequest(
        String front,
        String back,
        List<String> tags,
        String status
) {
}
