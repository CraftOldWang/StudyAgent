package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.ModelUsageProperties;
import com.studyagent.eval.ModelUsageRecorder;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.ToolChoice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class ObservedModelTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    private ModelUsageRecorder recorder(int limit) throws Exception {
        return new ModelUsageRecorder(new ModelUsageProperties(temporary.resolve("calls.jsonl"), limit), mapper);
    }

    private Model delegate() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        return model;
    }

    private List<JsonNode> events() throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(temporary.resolve("calls.jsonl"))) {
            result.add(mapper.readTree(line));
        }
        return result;
    }

    @Test
    void recordsFinalCumulativeUsageWithoutDoubleCountingCacheOrChunks() throws Exception {
        Model delegate = delegate();
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.just(
                ChatResponse.builder().usage(new ChatUsage(100, 2, 60, 0.1)).build(),
                ChatResponse.builder().usage(new ChatUsage(100, 12, 60, 0.2)).build()));
        ObservedModel model = new ObservedModel(delegate, recorder(10));

        model.stream(List.of(), List.of(), null).blockLast();

        List<JsonNode> events = events();
        assertThat(events).hasSize(2);
        assertThat(events.get(1).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(events.get(1).path("inputTokens").asInt()).isEqualTo(100);
        assertThat(events.get(1).path("cachedInputTokens").asInt()).isEqualTo(60);
        assertThat(events.get(1).path("totalTokens").asInt()).isEqualTo(112);
    }

    @Test
    void retryIsANewAttemptAndRestartDoesNotResetLimit() throws Exception {
        Model delegate = delegate();
        when(delegate.stream(any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("provider failed")))
                .thenReturn(Flux.just(ChatResponse.builder().build()));
        ObservedModel model = new ObservedModel(delegate, recorder(2));
        model.stream(List.of(), List.of(), null).retry(1).blockLast();

        List<JsonNode> events = events();
        assertThat(events).hasSize(4);
        assertThat(events.get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(events.get(1).path("usageAvailable").asBoolean()).isFalse();
        assertThat(events.get(1).has("inputTokens")).isFalse();
        assertThat(events.get(0).path("callId")).isNotEqualTo(events.get(2).path("callId"));
        ObservedModel restarted = new ObservedModel(delegate, recorder(2));
        assertThatThrownBy(() -> restarted.stream(List.of(), List.of(), null).blockLast())
                .hasMessageContaining("Model call limit reached");
        verify(delegate, times(2)).stream(any(), any(), any());
        assertThat(events()).hasSize(4);
    }

    @Test
    void cancellationIsRecordedAndMissingUsageIsUnknown() throws Exception {
        Model delegate = delegate();
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.never());
        ObservedModel model = new ObservedModel(delegate, recorder(10));
        var subscription = model.stream(List.of(), List.of(), null).subscribe();
        subscription.dispose();

        JsonNode terminal = events().get(1);
        assertThat(terminal.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(terminal.path("usageAvailable").asBoolean()).isFalse();
        assertThat(terminal.has("totalTokens")).isFalse();
    }

    @Test
    void reactorContextPreservesRequestTraceAcrossThreads() throws Exception {
        Model delegate = delegate();
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.just(ChatResponse.builder().build()));
        ObservedModel model = new ObservedModel(delegate, recorder(10));
        Flux<ChatResponse> request;
        ModelCallScope.bind(new ModelCallScope("request-trace", "POST /learning"));
        try {
            request = Flux.defer(() -> model.stream(List.of(), List.of(), null))
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(ModelCallScope.capture());
        } finally {
            ModelCallScope.clear();
        }
        request.blockLast();
        assertThat(events().getFirst().path("traceId").asText()).isEqualTo("request-trace");
        assertThat(events().getFirst().path("operation").asText()).isEqualTo("POST /learning");
    }

    @Test
    void preservesModelCapabilities() throws Exception {
        Model delegate = delegate();
        when(delegate.supportsNativeStructuredOutput()).thenReturn(true);
        when(delegate.supportsNativeStructuredOutputWithTools()).thenReturn(true);
        when(delegate.getContextWindowSize()).thenReturn(100_000);
        ObservedModel model = new ObservedModel(delegate, recorder(10));
        assertThat(model.supportsNativeStructuredOutput()).isTrue();
        assertThat(model.supportsNativeStructuredOutputWithTools()).isTrue();
        assertThat(model.getContextWindowSize()).isEqualTo(100_000);
    }

    @Test
    void providerCannotRetryBehindTheUsageBoundaryAndOtherOptionsSurvive() throws Exception {
        Model delegate = delegate();
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.just(ChatResponse.builder().build()));
        var options = GenerateOptions.builder().maxTokens(1800)
                .toolChoice(new ToolChoice.Specific("transition"))
                .executionConfig(ExecutionConfig.builder().maxAttempts(3).build()).build();
        new ObservedModel(delegate, recorder(10)).stream(List.of(), List.of(), options).blockLast();
        var sent = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(delegate).stream(any(), any(), sent.capture());
        assertThat(sent.getValue().getExecutionConfig().getMaxAttempts()).isEqualTo(1);
        assertThat(sent.getValue().getToolChoice()).isEqualTo(options.getToolChoice());
        assertThat(sent.getValue().getMaxTokens()).isEqualTo(1800);
    }
}
