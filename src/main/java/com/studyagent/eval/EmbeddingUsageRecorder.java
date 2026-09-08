package com.studyagent.eval;

import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.config.AiModelProperties;
import com.studyagent.config.EmbeddingUsageProperties;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class EmbeddingUsageRecorder {
    private final Path ledger;
    private final ObjectMapper mapper;

    public EmbeddingUsageRecorder(EmbeddingUsageProperties properties, ObjectMapper mapper) throws IOException {
        this.ledger = properties.ledger().toAbsolutePath();
        this.mapper = mapper;
        Files.createDirectories(ledger.getParent());
    }

    public synchronized String start(AiModelProperties.Embedding model, EmbeddingPurpose purpose, String text) {
        String callId = UUID.randomUUID().toString();
        ModelCallScope scope = ModelCallScope.current();
        if (scope == null) { scope = ModelCallScope.standalone(); }
        Map<String, Object> event = event(callId, "STARTED");
        event.put("boundary", "DASHSCOPE_SDK_CALL");
        event.put("traceId", scope.traceId());
        event.put("operation", scope.operation());
        event.put("model", model.model());
        event.put("dimensions", model.dimensions());
        event.put("purpose", purpose.name());
        event.put("contentSha256", sha256(text));
        append(event);
        return callId;
    }

    public synchronized void finish(String callId, String status, TextEmbeddingResult result,
            long elapsedNanos, Throwable error) {
        Map<String, Object> event = event(callId, status);
        Integer tokens = result == null || result.getUsage() == null ? null : result.getUsage().getTotalTokens();
        event.put("usageAvailable", tokens != null);
        if (tokens != null) { event.put("totalTokens", tokens); }
        if (result != null && result.getRequestId() != null) { event.put("providerRequestId", result.getRequestId()); }
        event.put("elapsedMillis", elapsedNanos / 1_000_000.0);
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
        try {
            Files.writeString(ledger, mapper.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot persist embedding usage ledger", ex);
        }
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK missing SHA-256", ex);
        }
    }
}
