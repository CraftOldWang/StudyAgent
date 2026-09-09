package com.studyagent.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AnkiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnkiConnectClient {
    private final AnkiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public AnkiConnectClient(AnkiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    public JsonNode call(String action, Map<String, ?> params) {
        String body;
        try {
            body = json.writeValueAsString(Map.of("action", action, "version", 6, "params", params));
        } catch (JsonProcessingException e) {
            throw new BusinessException("Anki 请求编码失败: " + action);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint()))
                .timeout(properties.timeout()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new BusinessException("AnkiConnect HTTP " + response.statusCode() + "，请检查本机 Anki");
            }
            JsonNode envelope = json.readTree(response.body());
            if (envelope == null || !envelope.has("error") || !envelope.has("result")) {
                throw new BusinessException("AnkiConnect 返回格式错误: " + action);
            }
            if (!envelope.get("error").isNull()) {
                throw new BusinessException("AnkiConnect " + action + " 失败: " + envelope.get("error").asText());
            }
            return envelope.get("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Anki 导出已中断，可重新导出确认结果");
        } catch (IOException e) {
            throw new BusinessException("无法连接 AnkiConnect 或响应无效，请打开本机 Anki 后重试（" + action + "）");
        }
    }
}
