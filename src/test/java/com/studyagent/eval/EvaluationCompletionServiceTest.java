package com.studyagent.eval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.agent.integration.ObservedModel;
import com.studyagent.config.ModelUsageProperties;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class EvaluationCompletionServiceTest {
    @TempDir Path temp;

    @Test
    void directEvaluationUsesTheSharedCountedModelAndTrace() throws Exception {
        Model delegate = mock(Model.class);
        when(delegate.getModelName()).thenReturn("test-model");
        when(delegate.stream(any(), any(), any())).thenReturn(Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("{\"ok\":true}").build()))
                .usage(new ChatUsage(100, 5, 0, 0.1)).build()));
        var ledger = temp.resolve("calls.jsonl");
        var recorder = new ModelUsageRecorder(new ModelUsageProperties(ledger, 1), new ObjectMapper());
        var service = new EvaluationCompletionService(new ObservedModel(delegate, recorder));
        ModelCallScope.bind(new ModelCallScope("trace-test", "HTTP"));
        try {
            assertThat(service.complete(1L, "gold-draft", "v1", "Return JSON", "question", 1000).text())
                    .isEqualTo("{\"ok\":true}");
            assertThatThrownBy(() -> service.complete(1L, "gold-draft", "v1", "Return JSON", "question", 1000))
                    .hasMessageContaining("limit");
        } finally { ModelCallScope.clear(); }
        verify(delegate, times(1)).stream(any(), any(), any());
        String events = Files.readString(ledger);
        assertThat(events).contains("trace-test", "EVAL/gold-draft/v1", "SUCCEEDED");
    }
}
