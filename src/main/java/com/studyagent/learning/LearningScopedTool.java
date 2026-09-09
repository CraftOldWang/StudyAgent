package com.studyagent.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.identity.IdentityScope;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Carry the server identity into blocking tools and capture the actual input/output under the same trace. */
public final class LearningScopedTool implements AgentTool {
    private final AgentTool delegate;
    private final Long userId;
    private final Long sessionId;
    private final ModelCallScope scope;
    private final IdentityScope identity;
    private final LearningTraceService traces;
    private final ObjectMapper mapper;
    private final java.util.function.Consumer<LearningConversationGateway.Progress> progress;

    public LearningScopedTool(AgentTool delegate, Long userId, Long sessionId, ModelCallScope scope,
                              IdentityScope identity, LearningTraceService traces, ObjectMapper mapper) {
        this(delegate, userId, sessionId, scope, identity, traces, mapper, event -> { });
    }

    public LearningScopedTool(AgentTool delegate, Long userId, Long sessionId, ModelCallScope scope,
                              IdentityScope identity, LearningTraceService traces, ObjectMapper mapper,
                              java.util.function.Consumer<LearningConversationGateway.Progress> progress) {
        this.delegate = delegate; this.userId = userId; this.sessionId = sessionId;
        this.scope = scope; this.identity = identity; this.traces = traces; this.mapper = mapper;
        this.progress = progress;
    }
    @Override public String getName() { return delegate.getName(); }
    @Override public String getDescription() { return delegate.getDescription(); }
    @Override public Map<String, Object> getParameters() { return delegate.getParameters(); }
    @Override public boolean isReadOnly() { return delegate.isReadOnly(); }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            ModelCallScope previous = ModelCallScope.current();
            try (var ignored = identity.bind(userId)) {
                ModelCallScope.bind(scope);
                String toolCallId = param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
                traces.recordDetail(userId, scope.traceId(), sessionId, "TOOL", "TOOL_CALL", getName(), "STARTED",
                        json(param.getInput()), null, toolCallId);
                long started = System.nanoTime();
                display(toolCallId, "STARTED", param.getInput(), null, null);
                try {
                    ToolResultBlock result = delegate.callAsync(param).contextWrite(c -> c.put(ModelCallScope.class, scope)).block();
                    if (result == null) { throw new IllegalStateException("学习工具未返回结果"); }
                    traces.recordDetail(userId, scope.traceId(), sessionId, "TOOL", "TOOL_RESULT", getName(), "SUCCEEDED",
                            json(result), (System.nanoTime() - started) / 1_000_000, toolCallId);
                    display(toolCallId, "SUCCEEDED", param.getInput(), result, null);
                    return result;
                } catch (RuntimeException error) {
                    display(toolCallId, "FAILED", param.getInput(), null, error.getMessage());
                    traces.recordDetail(userId, scope.traceId(), sessionId, "TOOL", "TOOL_RESULT", getName() + ": " + error.getMessage(), "FAILED",
                            null, (System.nanoTime() - started) / 1_000_000, toolCallId);
                    throw error;
                }
            } finally {
                if (previous == null) { ModelCallScope.clear(); } else { ModelCallScope.bind(previous); }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    private void display(String id, String status, Object input, Object output, String error) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id); data.put("name", getName()); data.put("status", status);
        data.put("input", LearningToolDisplay.input(mapper, getName(), input)); data.put("output", output); data.put("error", error);
        progress.accept(new LearningConversationGateway.Progress("tool", json(data)));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("工具 trace 序列化失败", e); }
    }
}
