package com.studyagent.modules.evaluation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.config.AiModelProperties;
import com.studyagent.common.exception.BusinessException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 评测集生成客户端。
 *
 * <p>这里直接调用 DeepSeek OpenAI-compatible 的 chat completions 接口，而不是复用普通 ChatGenerationService。
 * 原因是评测集生成需要 response_format=json_object，让模型尽量返回可解析 JSON，减少人工清洗成本。</p>
 */
@Component
public class DeepseekEvalCaseClient {

    private final AiModelProperties aiModelProperties;
    private final ObjectMapper objectMapper;
    private final DeepseekGeneratedCasesParser parser;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DeepseekEvalCaseClient(
            AiModelProperties aiModelProperties,
            ObjectMapper objectMapper,
            DeepseekGeneratedCasesParser parser
    ) {
        this.aiModelProperties = aiModelProperties;
        this.objectMapper = objectMapper;
        this.parser = parser;
    }

    /**
     * 根据 source chunks 生成临时评测集。
     */
    public GeneratedRagEvalDataset generate(
            List<GeneratedRagEvalDataset.SourceChunk> sourceChunks,
            int caseCount
    ) {
        if (sourceChunks == null || sourceChunks.isEmpty()) {
            throw new BusinessException("生成评测集需要至少一个源 chunk");
        }
        AiModelProperties.Chat chat = requiredChat();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", defaultString(chat.model(), "deepseek-chat"));
        body.put("temperature", chat.temperature() == null ? 0.2d : chat.temperature());
        body.put("max_tokens", chat.maxTokens() == null ? 1800 : chat.maxTokens());
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.add(message("system", systemPrompt()));
        messages.add(message("user", userPrompt(sourceChunks, caseCount)));

        JsonNode response = request(chat, body);
        String content = response.path("choices").path(0).path("message").path("content").asText();
        DeepseekGeneratedCasesParser.ParseResult parseResult = parser.parse(content, sourceChunks);
        return new GeneratedRagEvalDataset(parseResult.cases(), sourceChunks, parseResult.warnings());
    }

    /**
     * 系统提示词要求模型只使用给定 chunkId，并输出严格 JSON。
     */
    private String systemPrompt() {
        return """
                你是 RAG 检索评测集生成器。请只基于用户给出的 chunk 生成问题。
                目标是评测检索 Recall@K，因此每个问题都必须能从 expectedChunkIds 对应的 CHILD chunk 中直接找到依据。
                只返回 JSON，不要返回 Markdown。
                JSON 顶层格式必须是：
                {
                  "cases": [
                    {
                      "question": "问题文本",
                      "expectedAnswer": "简短参考答案",
                      "expectedChunkIds": [123],
                      "reason": "为什么这些 chunk 支撑该问题"
                    }
                  ]
                }
                expectedChunkIds 只能使用用户提供的 chunk_type=CHILD 的 chunk_id，禁止编造 ID。
                """;
    }

    /**
     * 用户提示词中包含候选 chunk。每个 chunk 保留 ID、文档、序号和截断后的内容，方便模型生成可追踪真值。
     */
    private String userPrompt(List<GeneratedRagEvalDataset.SourceChunk> sourceChunks, int caseCount) {
        StringBuilder builder = new StringBuilder();
        builder.append("请生成 ").append(caseCount).append(" 条用于 Recall@K 的 RAG 检索评测问题。\n");
        builder.append("要求：问题要覆盖不同知识点；不要生成需要外部常识的问题；expectedChunkIds 可以包含 1 到 3 个 chunk。\n\n");
        builder.append("候选 chunks：\n");
        for (GeneratedRagEvalDataset.SourceChunk chunk : sourceChunks) {
            builder.append("chunk_id=").append(chunk.chunkId())
                    .append(", document_id=").append(chunk.documentId())
                    .append(", knowledge_base_id=").append(chunk.knowledgeBaseId())
                    .append(", parent_chunk_id=").append(chunk.parentChunkId())
                    .append(", chunk_type=").append(chunk.chunkType())
                    .append(", chunk_index=").append(chunk.chunkIndex())
                    .append(", title=").append(nullToEmpty(chunk.documentTitle()))
                    .append("\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        builder.append("请输出 JSON。");
        return builder.toString();
    }

    private ObjectNode message(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    /**
     * 发送 DeepSeek 请求，非 2xx 响应直接暴露错误，避免把生成失败误认为空数据。
     */
    private JsonNode request(AiModelProperties.Chat chat, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(chatCompletionsUri(chat.baseUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + required(chat.apiKey(), "DeepSeek API Key 未配置，请设置 DEEPSEEK_API_KEY"))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new BusinessException("DeepSeek 生成评测集失败: " + response.statusCode() + " " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("DeepSeek 请求异常: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("DeepSeek 请求被中断");
        }
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalized = defaultString(baseUrl, "https://api.deepseek.com").replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        return URI.create(normalized + "/chat/completions");
    }

    private AiModelProperties.Chat requiredChat() {
        if (aiModelProperties.chat() == null) {
            throw new BusinessException("AI chat 配置不能为空");
        }
        if (!"deepseek".equalsIgnoreCase(aiModelProperties.chat().provider())) {
            throw new BusinessException("评测集生成当前仅支持 deepseek chat provider");
        }
        return aiModelProperties.chat();
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value;
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
