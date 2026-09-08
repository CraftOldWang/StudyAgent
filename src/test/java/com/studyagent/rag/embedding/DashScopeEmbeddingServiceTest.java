package com.studyagent.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.embeddings.TextEmbeddingUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.eval.EmbeddingUsageRecorder;
import com.studyagent.config.EmbeddingUsageProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AiModelProperties;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class DashScopeEmbeddingServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private EmbeddingUsageRecorder recorder() throws Exception {
        return new EmbeddingUsageRecorder(new EmbeddingUsageProperties(directory.resolve("embedding.jsonl")), mapper);
    }

    @Test
    void mapsDocumentAndQueryPurposeToOfficialSdkTextType() throws Exception {
        TextEmbedding client = mock(TextEmbedding.class);
        TextEmbeddingResult providerResult = result(0.25, 0.75);
        when(client.call(any(TextEmbeddingParam.class))).thenReturn(providerResult);
        DashScopeEmbeddingService service = new DashScopeEmbeddingService(client, properties(), recorder());

        assertThat(service.embed("document", EmbeddingPurpose.DOCUMENT)).containsExactly(0.25f, 0.75f);
        assertThat(service.embed("query", EmbeddingPurpose.QUERY)).containsExactly(0.25f, 0.75f);

        ArgumentCaptor<TextEmbeddingParam> captor = ArgumentCaptor.forClass(TextEmbeddingParam.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).call(captor.capture());
        assertThat(captor.getAllValues().get(0).getParameters().get("text_type"))
                .isEqualTo("document");
        assertThat(captor.getAllValues().get(1).getParameters().get("text_type"))
                .isEqualTo("query");
    }

    @Test
    void rejectsMissingPurposeBeforeCallingProvider() throws Exception {
        DashScopeEmbeddingService service = new DashScopeEmbeddingService(mock(TextEmbedding.class), properties(), recorder());

        assertThatThrownBy(() -> service.embed("text", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用途");
    }

    @Test
    void recordsProviderUsageAndTraceWithoutPersistingInput() throws Exception {
        TextEmbedding client = mock(TextEmbedding.class);
        TextEmbeddingResult result = result(0.25, 0.75);
        TextEmbeddingUsage usage = new TextEmbeddingUsage();
        usage.setTotalTokens(17);
        when(result.getUsage()).thenReturn(usage);
        when(result.getRequestId()).thenReturn("provider-request");
        when(client.call(any(TextEmbeddingParam.class))).thenReturn(result);
        ModelCallScope.bind(new ModelCallScope("http-trace", "POST /search"));
        try {
            new DashScopeEmbeddingService(client, properties(), recorder()).embed("private course text", EmbeddingPurpose.QUERY);
        } finally { ModelCallScope.clear(); }
        List<String> events = Files.readAllLines(directory.resolve("embedding.jsonl"));
        assertThat(events).hasSize(2);
        assertThat(String.join("", events)).doesNotContain("private course text", "test-key");
        assertThat(mapper.readTree(events.getFirst()).path("traceId").asText()).isEqualTo("http-trace");
        assertThat(mapper.readTree(events.getFirst()).path("contentSha256").asText()).hasSize(64);
        assertThat(mapper.readTree(events.getLast()).path("totalTokens").asInt()).isEqualTo(17);
        assertThat(mapper.readTree(events.getLast()).path("providerRequestId").asText()).isEqualTo("provider-request");
    }

    @Test
    void recordsUnknownFailureAndRetainsKnownUsageWhenVectorIsInvalid() throws Exception {
        TextEmbedding client = mock(TextEmbedding.class);
        TextEmbeddingResult result = result(0.25, 0.75, 1.0);
        TextEmbeddingUsage usage = new TextEmbeddingUsage();
        usage.setTotalTokens(17);
        when(result.getUsage()).thenReturn(usage);
        when(client.call(any(TextEmbeddingParam.class)))
                .thenThrow(new IllegalStateException("provider failure"))
                .thenReturn(result);
        var service = new DashScopeEmbeddingService(client, properties(), recorder());
        assertThatThrownBy(() -> service.embed("text", EmbeddingPurpose.DOCUMENT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.embed("text", EmbeddingPurpose.DOCUMENT))
                .hasMessageContaining("维度");
        List<String> events = Files.readAllLines(directory.resolve("embedding.jsonl"));
        assertThat(events).hasSize(4);
        assertThat(mapper.readTree(events.get(1)).path("usageAvailable").asBoolean()).isFalse();
        assertThat(mapper.readTree(events.get(1)).has("totalTokens")).isFalse();
        assertThat(mapper.readTree(events.get(3)).path("status").asText()).isEqualTo("FAILED");
        assertThat(mapper.readTree(events.get(3)).path("totalTokens").asInt()).isEqualTo(17);
    }

    private TextEmbeddingResult result(double... values) {
        TextEmbeddingResultItem item = new TextEmbeddingResultItem();
        item.setEmbedding(java.util.Arrays.stream(values).boxed().toList());
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(List.of(item));
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);
        return result;
    }

    private AiModelProperties properties() {
        return new AiModelProperties(
                new AiModelProperties.Embedding(
                        "dashscope", "text-embedding-v3", 2, "test-key", "https://dashscope.aliyuncs.com"),
                null);
    }
}
