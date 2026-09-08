package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ObservedModel;
import com.studyagent.eval.ModelUsageRecorder;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentScopeModelWireContractTest {
    @Test
    void eachRetriedHttpRequestConsumesOneDurableAttempt(@TempDir Path directory) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"error\":{\"message\":\"temporary test failure\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var properties = new AgentScopeModelProperties("deepseek:deepseek-v4-flash", null, 1, null,
                    new AgentScopeModelProperties.Provider("wire-test-key",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 1800, false, 0.2));
            Path ledger = directory.resolve("calls.jsonl");
            var model = new ObservedModel(AgentScopeModelConfiguration.resolve(properties.primaryModelId(), properties),
                    new ModelUsageRecorder(new ModelUsageProperties(ledger, 2), new ObjectMapper()));
            var call = model.stream(List.of(new UserMessage("test")), List.of(), GenerateOptions.builder()
                    .executionConfig(io.agentscope.core.model.ExecutionConfig.builder().maxAttempts(3).build()).build());
            assertThatThrownBy(() -> call.retry(1).blockLast(Duration.ofSeconds(15))).isInstanceOf(RuntimeException.class);
            assertThat(requests.get()).isEqualTo(2);
            assertThat(Files.readAllLines(ledger)).hasSize(4);
            assertThatThrownBy(() -> call.blockLast(Duration.ofSeconds(15))).hasMessageContaining("Model call limit reached");
            assertThat(requests.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesPerCallToolChoiceAlongsideProviderDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = ("data: {\"id\":\"wire-test\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var properties = new AgentScopeModelProperties("deepseek:deepseek-v4-flash", null, 1, null,
                    new AgentScopeModelProperties.Provider("wire-test-key",
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 1800, false, 0.2));
            var model = AgentScopeModelConfiguration.resolve(properties.primaryModelId(), properties);
            model.stream(List.of(new UserMessage("test")), List.of(ToolSchema.builder()
                            .name("learning_state_transition").description("advance")
                            .parameters(Map.of("type", "object", "properties", Map.of())).build()),
                    GenerateOptions.builder().toolChoice(new ToolChoice.Specific("learning_state_transition")).build())
                    .blockLast(Duration.ofSeconds(15));
            assertThat(request.get().path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(request.get().path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(request.get().path("max_tokens").asInt()).isEqualTo(1800);
            assertThat(request.get().path("tool_choice").path("function").path("name").asText())
                    .isEqualTo("learning_state_transition");
        } finally {
            server.stop(0);
        }
    }
}
