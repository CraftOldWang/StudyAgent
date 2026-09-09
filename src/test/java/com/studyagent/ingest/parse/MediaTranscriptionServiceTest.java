package com.studyagent.ingest.parse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.AsrProperties;
import com.studyagent.config.DocumentPipelineProperties;
import com.studyagent.ingest.pipeline.DocumentPipelinePersistence;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MediaTranscriptionServiceTest {
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final DocumentPipelinePersistence persistence = mock(DocumentPipelinePersistence.class);
    private final ObjectMapper json = new ObjectMapper();
    private static final String RESULT = """
            {"processorVersion":"test-v1","inputSha256":"hash","durationMs":2200,"elapsedMs":100,
             "segments":[{"start":1.25,"end":2.2,"text":"队列先进先出。"}]}
            """;

    MediaTranscriptionService service(String url) {
        return new MediaTranscriptionService(storage, persistence,
                new AsrProperties(url, Duration.ofSeconds(5), "test-v1"),
                new DocumentPipelineProperties(Duration.ofMillis(90), Duration.ofSeconds(1), 10), json);
    }

    Document document() {
        var doc = new Document(); doc.setId(1L); doc.setUserId(2L); doc.setFileRecordId(3L);
        return doc;
    }

    @Test void cachedTranscriptionResumesWithoutCallingWorkerOrReadingOriginalMedia() {
        Document doc = document(); doc.setParserVersion("test-v1"); doc.setAsrResultKey("saved.json");
        when(storage.getObject("saved.json")).thenReturn(new ByteArrayInputStream(RESULT.getBytes(StandardCharsets.UTF_8)));
        assertThat(service("http://127.0.0.1:1").transcribe(doc)).isEqualTo("[00:00:01.250–00:00:02.200] 队列先进先出。");
        verify(persistence, never()).loadFile(anyLong());
        verify(persistence, never()).markTranscribed(any(), any(), any(), any());
    }

    @Test void invalidVersionOrEmptySegmentsCannotBecomeParsedText() throws Exception {
        var service = service("http://127.0.0.1:1");
        assertThatThrownBy(() -> service.transcript(json.readTree("{\"processorVersion\":\"other\",\"segments\":[]}")))
                .hasMessageContaining("版本");
        assertThatThrownBy(() -> service.transcript(json.readTree("{\"processorVersion\":\"test-v1\",\"segments\":[]}")))
                .hasMessageContaining("没有有效语音");
        assertThatThrownBy(() -> service.transcript(null)).hasMessageContaining("版本");
    }

    @Test void negativeOrReversedTimestampsAreRejected() throws Exception {
        var service = service("http://127.0.0.1:1");
        assertThatThrownBy(() -> service.transcript(json.readTree("""
                {"processorVersion":"test-v1","segments":[{"start":2,"end":1,"text":"text"}]}
                """))).hasMessageContaining("时间无效");
    }

    @Test void waitingForAsrRenewsLeaseAndPersistsResultBeforeReturningText() throws Exception {
        Document doc = document();
        var file = new FileRecord(); file.setStorageKey("media"); file.setFileHash("hash");
        when(persistence.loadFile(3L)).thenReturn(file);
        when(storage.getObject("media")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        var renewedWhileWaiting = new CountDownLatch(1);
        var renewals = new AtomicInteger();
        doAnswer(call -> { if (renewals.incrementAndGet() >= 2) renewedWhileWaiting.countDown(); return null; })
                .when(persistence).renewLease(doc);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Content-Sha256")).isEqualTo("hash");
            assertThat(exchange.getRequestBody().readAllBytes()).containsExactly((byte)1, (byte)2, (byte)3);
            try { assertThat(renewedWhileWaiting.await(2, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            byte[] bytes = RESULT.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            String text = service("http://127.0.0.1:" + server.getAddress().getPort()).transcribe(doc);
            assertThat(text).contains("队列先进先出");
            verify(persistence, atLeast(2)).renewLease(doc);
            var order = inOrder(storage, persistence);
            order.verify(storage).putObject(startsWith("asr/2/1/test-v1/"), any(), anyLong(), eq("application/json"));
            order.verify(persistence).markTranscribed(eq(doc), startsWith("asr/2/1/test-v1/"), contains("durationMs"), eq("test-v1"));
        } finally { server.stop(0); }
    }
}
