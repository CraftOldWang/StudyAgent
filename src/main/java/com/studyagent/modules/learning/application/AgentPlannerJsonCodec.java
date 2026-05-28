package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent Planner JSON 解析与校验器。
 *
 * <p>这是双层 LLM 架构里最重要的防线：Planner 的原始输出永远不直接发给前端，也不直接推进数据库状态。
 * 只有通过这里的枚举、完成条件和 summary 校验后，后端状态机才会采用该 decision。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentPlannerJsonCodec {

    private static final Set<String> ALLOWED_PHASES = Set.of("TEACH", "QA", "QUIZ", "SUMMARY");
    private static final Set<String> ALLOWED_TOPIC_STATUS = Set.of("IN_PROGRESS", "NEEDS_USER_INPUT", "DONE");
    private static final Set<String> ALLOWED_NEXT_ACTIONS = Set.of(
            "WAIT_USER",
            "CONTINUE_TOPIC",
            "COMPLETE_TOPIC",
            "MOVE_NEXT_TOPIC",
            "FINISH_SESSION"
    );

    private final ObjectMapper objectMapper;

    /**
     * 解析 Planner 输出。
     */
    public AgentPlannerDecision parseDecision(String rawOutput) {
        JsonNode root = readJsonObject(rawOutput);
        return normalize(new AgentPlannerDecision(
                requiredText(root, "phase", "decision.phase 不能为空"),
                requiredText(root, "currentTopicStatus", "decision.currentTopicStatus 不能为空"),
                requiredText(root, "nextAction", "decision.nextAction 不能为空"),
                requiredText(root, "responsePlan", "decision.responsePlan 不能为空"),
                optionalText(root, "summary"),
                optionalText(root, "reason")
        ));
    }

    /**
     * 输出给 repair prompt 的 JSON Schema 描述。
     */
    public String decisionSchemaText() {
        return """
                {
                  "phase": "TEACH|QA|QUIZ|SUMMARY",
                  "currentTopicStatus": "IN_PROGRESS|NEEDS_USER_INPUT|DONE",
                  "nextAction": "WAIT_USER|CONTINUE_TOPIC|COMPLETE_TOPIC|MOVE_NEXT_TOPIC|FINISH_SESSION",
                  "responsePlan": "给 Response Writer 的写作计划，不直接展示给用户",
                  "summary": "只有 currentTopicStatus=DONE 时必填，用于 topic summary 和 context snapshot",
                  "reason": "给后端审计/调试看的简短原因"
                }
                """;
    }

    /**
     * 规范化枚举大小写，并集中校验状态机约束。
     */
    private AgentPlannerDecision normalize(AgentPlannerDecision decision) {
        String phase = upper(decision.phase());
        String status = upper(decision.currentTopicStatus());
        String nextAction = upper(decision.nextAction());
        String responsePlan = trimToEmpty(decision.responsePlan());
        String summary = trimToEmpty(decision.summary());
        String reason = trimToEmpty(decision.reason());

        if (!ALLOWED_PHASES.contains(phase)) {
            throw new BusinessException("非法 Agent phase: " + decision.phase());
        }
        if (!ALLOWED_TOPIC_STATUS.contains(status)) {
            throw new BusinessException("非法知识点状态: " + decision.currentTopicStatus());
        }
        if (!ALLOWED_NEXT_ACTIONS.contains(nextAction)) {
            throw new BusinessException("非法下一步动作: " + decision.nextAction());
        }
        if ("NEEDS_USER_INPUT".equals(status) && !"WAIT_USER".equals(nextAction)) {
            throw new BusinessException("知识点需要用户输入时，nextAction 必须是 WAIT_USER");
        }
        if ("DONE".equals(status) && !Set.of("COMPLETE_TOPIC", "MOVE_NEXT_TOPIC", "FINISH_SESSION").contains(nextAction)) {
            throw new BusinessException("知识点完成时，nextAction 必须进入完成类动作");
        }
        if (!"DONE".equals(status) && Set.of("COMPLETE_TOPIC", "MOVE_NEXT_TOPIC", "FINISH_SESSION").contains(nextAction)) {
            throw new BusinessException("未完成知识点不能执行完成类动作");
        }
        if ("DONE".equals(status) && summary.isBlank()) {
            throw new BusinessException("完成知识点时必须提供 summary，用于压缩本轮记忆");
        }
        return new AgentPlannerDecision(phase, status, nextAction, responsePlan, summary, reason);
    }

    /**
     * 读取 JSON 对象。允许模型偶尔包一层 Markdown，但只截取第一个完整 JSON 对象。
     */
    private JsonNode readJsonObject(String rawOutput) {
        String json = extractJsonObject(rawOutput);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                throw new BusinessException("Planner 输出必须是 JSON 对象");
            }
            return root;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("解析 Planner JSON 输出失败: " + ex.getMessage());
        }
    }

    /**
     * 使用状态机扫描 JSON 边界，避免字符串里的大括号影响截取。
     */
    private String extractJsonObject(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BusinessException("Planner 输出为空，无法解析 JSON");
        }
        String text = rawOutput.trim();
        int start = text.indexOf('{');
        if (start < 0) {
            throw new BusinessException("Planner 输出中没有 JSON 对象");
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        throw new BusinessException("Planner 输出中的 JSON 对象不完整");
    }

    private String requiredText(JsonNode node, String field, String message) {
        String value = optionalText(node, field);
        if (value.isBlank()) {
            throw new BusinessException(message);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private String upper(String value) {
        return trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
