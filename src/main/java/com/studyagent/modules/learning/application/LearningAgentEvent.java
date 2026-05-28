package com.studyagent.modules.learning.application;

/**
 * SSE 事件载体，event 是事件名，data 是结构化事件内容。
 */
public record LearningAgentEvent(
        String event,
        Object data
) {
}
