package com.studyagent.modules.learning.application;

/**
 * Agent Planner 输出的结构化决策。
 *
 * <p>Planner 只负责“后端下一步应该怎么做”的控制面判断，不直接生成用户可见长文本。这样可以把
 * tool calling、有限状态机状态转移和自然语言教学回复拆开：模型 JSON 只进入后端校验边界，前端只看
 * 后端确认过的 SSE 事件和 Writer 的纯文本 token。</p>
 */
public record AgentPlannerDecision(
        String phase,
        String currentTopicStatus,
        String nextAction,
        String responsePlan,
        String summary,
        String reason
) {
}
