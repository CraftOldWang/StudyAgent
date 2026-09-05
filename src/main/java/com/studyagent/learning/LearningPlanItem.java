package com.studyagent.learning;

import java.util.List;

/**
 * 学习计划中的一个知识点。
 */
public record LearningPlanItem(
        String topic,
        List<String> subtopics,
        int estimatedMinutes
) {
}
