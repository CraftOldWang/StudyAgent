package com.studyagent.review;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.studyagent.config.AnkiProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnkiConnectClientTest {
    @Test void numericNoteIdsSurviveApplicationLongStringSerializer() throws Exception {
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] result = "{\"result\":[],\"error\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result); exchange.close();
        });
        server.start();
        try {
            var json = new ObjectMapper().registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance));
            var client = new AnkiConnectClient(new AnkiProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofSeconds(2), "StudyPilot v1", "StudyPilot"), json);
            client.call("notesInfo", Map.of("notes", List.of(LongNode.valueOf(1789000000000L))));
            assertThat(json.readTree(captured.get()).path("params").path("notes").get(0).isIntegralNumber()).isTrue();
        } finally { server.stop(0); }
    }

    @Test void unavailableAnkiIsAnExplicitRetryableFailure() throws Exception {
        var socket = new java.net.ServerSocket(0);
        int port = socket.getLocalPort(); socket.close();
        var client = new AnkiConnectClient(new AnkiProperties("http://127.0.0.1:" + port,
                Duration.ofMillis(300), "StudyPilot v1", "StudyPilot"), new ObjectMapper());
        assertThatThrownBy(() -> client.call("version", Map.of())).hasMessageContaining("打开本机 Anki 后重试");
    }
}
