package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.infrastructure.ai.ToolCallEventListener;
import com.studyagent.modules.tool.application.KnowledgeSearchTool;
import com.studyagent.modules.tool.application.ReviewCardWriteTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 收集 Planner 触发的工具调用轨迹，并同步转换为 SSE 事件。
 *
 * <p>Spring AI 的 tool calling 发生在模型调用内部。为了让前端看到“正在检索/写卡/失败”的过程，同时又让
 * Response Writer 能读到工具结果摘要，这里把工具事件汇总成一次 run 内的 trace。工具真实鉴权和审计仍在
 * tool 模块内部完成，本类只做观测和事件整形。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolCallTraceCollector {

    private final ObjectMapper objectMapper;

    /**
     * 为一次 Planner 调用创建独立 trace。
     */
    public Trace create(Consumer<LearningAgentEvent> sink) {
        return new Trace(objectMapper, sink);
    }

    public static class Trace implements ToolCallEventListener {

        private final ObjectMapper objectMapper;
        private final Consumer<LearningAgentEvent> sink;
        private final List<ToolCallTrace> traces = new ArrayList<>();

        private Trace(ObjectMapper objectMapper, Consumer<LearningAgentEvent> sink) {
            this.objectMapper = objectMapper;
            this.sink = sink;
        }

        @Override
        public void onToolStarted(String toolName, String argumentsJson) {
            traces.add(new ToolCallTrace(toolName, "STARTED", safeJson(argumentsJson), "", Map.of()));
            emit("tool.started", Map.of(
                    "toolName", toolName,
                    "arguments", safeJson(argumentsJson)
            ));
        }

        @Override
        public void onToolCompleted(String toolName, String result) {
            Object resultObject = safeJson(result);
            Map<String, Object> summary = toolResultSummary(toolName, result);
            traces.add(new ToolCallTrace(toolName, "COMPLETED", "", resultObject, summary));
            emit("tool.completed", Map.of(
                    "toolName", toolName,
                    "result", resultObject,
                    "summary", summary
            ));
        }

        @Override
        public void onToolFailed(String toolName, String argumentsJson, String errorMessage) {
            Map<String, Object> summary = Map.of("message", errorMessage == null ? "" : errorMessage);
            traces.add(new ToolCallTrace(toolName, "FAILED", safeJson(argumentsJson), "", summary));
            emit("tool.failed", Map.of(
                    "toolName", toolName,
                    "arguments", safeJson(argumentsJson),
                    "message", errorMessage == null ? "" : errorMessage
            ));
        }

        /**
         * 给 Agent step 记录和 Writer prompt 使用的结构化轨迹。
         */
        public List<ToolCallTrace> traces() {
            return List.copyOf(traces);
        }

        /**
         * Writer 不需要完整工具 JSON，只需要知道发生了什么、是否成功、可引用资料的摘要。
         */
        public String summaryText() {
            if (traces.isEmpty()) {
                return "本轮 Planner 未调用工具。";
            }
            StringBuilder builder = new StringBuilder();
            for (ToolCallTrace trace : traces) {
                builder.append("- ")
                        .append(trace.toolName())
                        .append(" ")
                        .append(trace.status());
                if (!trace.summary().isEmpty()) {
                    builder.append(" ")
                            .append(trace.summary());
                }
                builder.append("\n");
            }
            return builder.toString();
        }

        /**
         * 从 knowledge_search 工具结果中提炼可引用片段，交给 Writer 生成自然语言引用。
         */
        public String referenceSummaryText() {
            StringBuilder builder = new StringBuilder();
            int index = 1;
            for (ToolCallTrace trace : traces) {
                if (!KnowledgeSearchTool.TOOL_NAME.equals(trace.toolName()) || !"COMPLETED".equals(trace.status())) {
                    continue;
                }
                Object result = trace.result();
                if (!(result instanceof Map<?, ?> resultMap)) {
                    continue;
                }
                Object references = resultMap.get("references");
                if (!(references instanceof List<?> referenceList) || referenceList.isEmpty()) {
                    builder.append("知识库未检索到相关内容。\n");
                    continue;
                }
                for (Object item : referenceList) {
                    if (!(item instanceof Map<?, ?> reference)) {
                        continue;
                    }
                    builder.append(index++)
                            .append(". document=")
                            .append(mapValue(reference, "documentTitle", "未知文档"))
                            .append(", chunkId=")
                            .append(mapValue(reference, "chunkId", ""))
                            .append(", content=")
                            .append(compact(mapValue(reference, "content", ""), 360))
                            .append("\n");
                }
            }
            if (builder.isEmpty()) {
                return "无可引用资料摘要。";
            }
            return builder.toString();
        }

        private void emit(String event, Object data) {
            if (sink != null) {
                sink.accept(new LearningAgentEvent(event, data));
            }
        }

        /**
         * 工具结果可能是 JSON，也可能是普通字符串；SSE 和 Writer 都尽量拿结构化信息。
         */
        private Object safeJson(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            try {
                return objectMapper.readValue(value, Object.class);
            } catch (Exception ex) {
                return compact(value, 1200);
            }
        }

        /**
         * 给前端和 Writer 一个稳定摘要，避免它们理解每个工具的完整返回结构。
         */
        private Map<String, Object> toolResultSummary(String toolName, String result) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {
                });
                if (KnowledgeSearchTool.TOOL_NAME.equals(toolName)) {
                    return Map.of("hitCount", parsed.getOrDefault("hitCount", 0));
                }
                if (ReviewCardWriteTool.TOOL_NAME.equals(toolName)) {
                    return Map.of("cardCount", parsed.getOrDefault("cardCount", 0));
                }
            } catch (Exception ignored) {
                // 摘要解析失败不代表工具失败；真实状态以工具调用异常和审计记录为准。
            }
            return Map.of();
        }

        private String compact(String content, int maxLength) {
            if (content == null) {
                return "";
            }
            String normalized = content.replaceAll("\\s+", " ").trim();
            if (normalized.length() <= maxLength) {
                return normalized;
            }
            return normalized.substring(0, maxLength) + "...";
        }

        private String mapValue(Map<?, ?> map, String key, String defaultValue) {
            Object value = map.get(key);
            return value == null ? defaultValue : String.valueOf(value);
        }
    }

    /**
     * 单个工具调用事件快照。
     */
    public record ToolCallTrace(
            String toolName,
            String status,
            Object arguments,
            Object result,
            Map<String, Object> summary
    ) {
    }
}
