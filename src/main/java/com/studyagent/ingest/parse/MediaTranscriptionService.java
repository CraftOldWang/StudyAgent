package com.studyagent.ingest.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AsrProperties;
import com.studyagent.config.DocumentPipelineProperties;
import com.studyagent.ingest.pipeline.DocumentPipelinePersistence;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MediaTranscriptionService {
    private final ObjectStorageService storage;
    private final DocumentPipelinePersistence persistence;
    private final AsrProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private final long heartbeatMs;

    public MediaTranscriptionService(ObjectStorageService storage, DocumentPipelinePersistence persistence,
            AsrProperties properties, DocumentPipelineProperties pipeline, ObjectMapper json) {
        this.storage = storage; this.persistence = persistence; this.properties = properties; this.json = json;
        this.heartbeatMs = Math.max(1, Math.min(10_000, pipeline.leaseDuration().toMillis() / 3));
    }

    public static boolean isMedia(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4a|mp3|wav)$");
    }

    public String processorVersion() { return properties.processorVersion(); }

    public String transcribe(Document document) {
        if (properties.processorVersion().equals(document.getParserVersion()) && document.getAsrResultKey() != null) {
            try (var input = storage.getObject(document.getAsrResultKey())) {
                log.info("复用转写产物: documentId={}", document.getId());
                return transcript(json.readTree(input));
            } catch (IOException e) {
                throw new BusinessException("读取转写产物失败: " + e.getMessage());
            }
        }
        FileRecord file = persistence.loadFile(document.getFileRecordId());
        if (file == null) throw new BusinessException("音视频文件记录不存在");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("studypilot-asr-", ".media");
            try (var input = storage.getObject(file.getStorageKey()); var output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
            persistence.renewLease(document);
            var request = HttpRequest.newBuilder(URI.create(properties.endpoint())).timeout(properties.timeout())
                    .header("Content-Type", "application/octet-stream").header("X-Content-Sha256", file.getFileHash())
                    .POST(HttpRequest.BodyPublishers.ofFile(temporary)).build();
            var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try {
                while (true) {
                    try {
                        response = future.get(heartbeatMs, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException waiting) {
                        persistence.renewLease(document);
                    }
                }
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
            if (response.statusCode() != 200) {
                throw new BusinessException("转写服务 HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(1024, response.body().length())));
            }
            JsonNode result = json.readTree(response.body());
            String text = transcript(result);
            if (!file.getFileHash().equals(result.path("inputSha256").asText())) {
                throw new BusinessException("转写结果与音视频内容哈希不匹配");
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            String digest = digest(bytes);
            String key = "asr/" + document.getUserId() + "/" + document.getId() + "/" + properties.processorVersion() + "/" + digest + ".json";
            storage.putObject(key, new ByteArrayInputStream(bytes), bytes.length, "application/json");
            var metadata = result.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).remove("segments");
            persistence.markTranscribed(document, key, json.writeValueAsString(metadata), properties.processorVersion());
            log.info("转写完成: documentId={}, durationMs={}, elapsedMs={}, cacheHit={}", document.getId(),
                    result.path("durationMs").asLong(), result.path("elapsedMs").asLong(), result.path("cacheHit").asBoolean());
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("音视频转写等待被中断，可恢复原处理任务");
        } catch (ExecutionException e) {
            throw new BusinessException("本机转写服务不可用或执行超时: " + e.getCause().getClass().getSimpleName());
        } catch (IOException e) {
            throw new BusinessException("音视频转写文件或响应读取失败: " + e.getMessage());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { log.warn("转写临时文件清理失败: {}", temporary, e); }
            }
        }
    }

    String transcript(JsonNode result) {
        if (result == null || !properties.processorVersion().equals(result.path("processorVersion").asText())) {
            throw new BusinessException("转写服务版本与配置不一致");
        }
        var segments = result.path("segments");
        if (!segments.isArray() || segments.isEmpty()) throw new BusinessException("转写结果没有有效语音文本");
        StringBuilder text = new StringBuilder();
        for (var segment : segments) {
            String spoken = segment.path("text").asText().strip();
            double start = segment.path("start").asDouble(-1), end = segment.path("end").asDouble(-1);
            if (spoken.isBlank() || !Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end < start) {
                throw new BusinessException("转写片段文本或时间无效");
            }
            text.append('[').append(time(start)).append("–").append(time(end)).append("] ").append(spoken).append("\n\n");
        }
        return text.toString().strip();
    }

    private String time(double seconds) {
        long ms = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
