package com.studyagent.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ObservedToolCallbackTest {

    @Test
    void callShouldEmitStartedAndCompleted() {
        List<String> events = new ArrayList<>();
        ToolCallEventListener listener = new RecordingListener(events);
        ObservedToolCallback callback = new ObservedToolCallback(new StubToolCallback(false), listener);

        String result = callback.call("{\"query\":\"RRF\"}", new ToolContext(java.util.Map.of()));

        assertThat(result).isEqualTo("{\"hitCount\":1}");
        assertThat(events).containsExactly(
                "started:knowledge_search:{\"query\":\"RRF\"}",
                "completed:knowledge_search:{\"hitCount\":1}"
        );
    }

    @Test
    void callShouldEmitFailedAndRethrow() {
        List<String> events = new ArrayList<>();
        ToolCallEventListener listener = new RecordingListener(events);
        ObservedToolCallback callback = new ObservedToolCallback(new StubToolCallback(true), listener);

        assertThatThrownBy(() -> callback.call("{\"query\":\"RRF\"}", new ToolContext(java.util.Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
        assertThat(events).containsExactly(
                "started:knowledge_search:{\"query\":\"RRF\"}",
                "failed:knowledge_search:{\"query\":\"RRF\"}:boom"
        );
    }

    private record RecordingListener(List<String> events) implements ToolCallEventListener {
        @Override
        public void onToolStarted(String toolName, String argumentsJson) {
            events.add("started:" + toolName + ":" + argumentsJson);
        }

        @Override
        public void onToolCompleted(String toolName, String result) {
            events.add("completed:" + toolName + ":" + result);
        }

        @Override
        public void onToolFailed(String toolName, String argumentsJson, String errorMessage) {
            events.add("failed:" + toolName + ":" + argumentsJson + ":" + errorMessage);
        }
    }

    private record StubToolCallback(boolean fail) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("knowledge_search")
                    .description("search")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return "{\"hitCount\":1}";
        }
    }
}
