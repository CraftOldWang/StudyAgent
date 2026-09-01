package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 学习计划的 PLAN JSON 输出解析器。
 *
 * <p>Topic Loop 的状态决策已经迁移到 {@link AgentPlannerJsonCodec}。这里仅保留 PLAN 阶段的 Todo
 * 拆解解析，避免旧的“reply + decision 同模型输出”路径继续扩散。</p>
 */
@Component
@RequiredArgsConstructor
public class LearningPlanJsonCodec {

    private final ObjectMapper objectMapper;

    /**
     * 解析 PLAN 阶段的 Todo 列表。
     */
    public TodoPlanResult parsePlan(String rawOutput) {
        JsonNode root = readJsonObject(rawOutput);
        JsonNode todosNode = root.path("todos");
        if (!todosNode.isArray() || todosNode.isEmpty()) {
            throw new BusinessException("PLAN 输出缺少非空 todos 数组");
        }

        List<TodoPlanItem> todos = new ArrayList<>();
        for (JsonNode node : todosNode) {
            String title = requiredText(node, "title", "Todo 标题不能为空");
            String description = optionalText(node, "description");
            todos.add(new TodoPlanItem(compact(title, 255), compact(description, 1200)));
        }
        if (todos.size() > 12) {
            throw new BusinessException("PLAN 输出的 Todo 数量过多，最多允许 12 个");
        }
        return new TodoPlanResult(List.copyOf(todos));
    }

    /**
     * 读取 JSON 对象。模型偶尔会包一层 Markdown 或解释文字，这里只抽取第一个完整 JSON 对象。
     */
    private JsonNode readJsonObject(String rawOutput) {
        String json = extractJsonObject(rawOutput);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                throw new BusinessException("模型输出必须是 JSON 对象");
            }
            return root;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("解析模型 JSON 输出失败: " + ex.getMessage());
        }
    }

    /**
     * 从模型原始输出中截取第一个完整 JSON 对象。
     *
     * <p>这里不用简单的 firstIndex/lastIndex，是因为 reply 或 summary 字符串里也可能包含大括号。
     * 扫描时保留字符串与转义状态，可以避免把字符串内容误判成 JSON 结构边界。</p>
     */
    private String extractJsonObject(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new BusinessException("模型输出为空，无法解析 JSON");
        }
        String text = rawOutput.trim();
        int start = text.indexOf('{');
        if (start < 0) {
            throw new BusinessException("模型输出中没有 JSON 对象");
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
        throw new BusinessException("模型输出中的 JSON 对象不完整");
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

    private String compact(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    /**
     * PLAN 阶段解析结果。
     */
    public record TodoPlanResult(List<TodoPlanItem> todos) {
    }

    /**
     * 单个 Todo 草稿。
     */
    public record TodoPlanItem(String title, String description) {
    }

}
