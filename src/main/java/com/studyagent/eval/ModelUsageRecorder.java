package com.studyagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.config.ModelUsageProperties;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable attempt accounting at the shared Model boundary, including retry subscriptions. */
public final class ModelUsageRecorder {
    private final Path ledger;
    private final int maxCalls;
    private final ObjectMapper mapper;
    private long startedCalls;

    public ModelUsageRecorder(ModelUsageProperties properties, ObjectMapper mapper) throws IOException {
        this.ledger = properties.ledger().toAbsolutePath();
        this.maxCalls = properties.maxCalls();
        this.mapper = mapper;
        Files.createDirectories(ledger.getParent());
        if (Files.exists(ledger)) {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                if ("STARTED".equals(mapper.readTree(line).path("status").asText())) {
                    startedCalls++;
                }
            }
        }
    }

    public synchronized String start(String model, ModelCallScope scope, int messageCount,
            List<ToolSchema> tools, GenerateOptions options) {
        if (startedCalls >= maxCalls) {
            throw new IllegalStateException("Model call limit reached: " + maxCalls);
        }
        String callId = UUID.randomUUID().toString();
        Map<String, Object> event = event(callId, "STARTED");
        event.put("model", model);
        event.put("traceId", scope.traceId());
        event.put("operation", scope.operation());
        event.put("attemptNumber", startedCalls + 1);
        event.put("messageCount", messageCount);
        event.put("tools", tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList());
        ToolChoice choice = options == null ? null : options.getToolChoice();
        event.put("toolChoice", choice == null ? "DEFAULT" : choice.toString());
        append(event);
        startedCalls++;
        return callId;
    }

    public synchronized void finish(String callId, String status, ChatUsage usage, long elapsedNanos,
            Throwable error) {
        Map<String, Object> event = event(callId, status);
        event.put("elapsedMillis", elapsedNanos / 1_000_000.0);
        event.put("usageAvailable", usage != null);
        if (usage != null) {
            event.put("inputTokens", usage.getInputTokens());
            event.put("outputTokens", usage.getOutputTokens());
            event.put("cachedInputTokens", usage.getCachedTokens());
            event.put("totalTokens", (long) usage.getInputTokens() + usage.getOutputTokens());
        }
        // Exception messages may contain provider request data; preserve the type only here.
        if (error != null) { event.put("errorType", error.getClass().getName()); }
        append(event);
    }

    private Map<String, Object> event(String callId, String status) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("callId", callId);
        event.put("status", status);
        event.put("recordedAt", LocalDateTime.now().toString());
        return event;
    }

    private void append(Map<String, Object> event) {
        try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(mapper.writeValueAsString(event) + "\n");
            while (bytes.hasRemaining()) { channel.write(bytes); }
            channel.force(true);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot persist model usage ledger", ex);
        }
    }
}
