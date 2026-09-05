package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class LearningContextCompactorTest {

    @Test
    void oneOffContractReplacesSameStateAndSavesIt(@TempDir Path workspace) {
        RecordingModel model = new RecordingModel();
        AgentStateStore stateStore = mock(AgentStateStore.class);
        when(stateStore.get(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
        RuntimeContext context = RuntimeContext.builder().userId("1").sessionId("session-1").build();

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("learning-agent")
                .agentId("learning-agent")
                .model(model)
                .workspace(workspace)
                .stateStore(stateStore)
                .disableCompaction()
                .disableSubagents()
                .build()) {
            agent.call(new UserMessage("learning goal: Java generics"), context).block();
            agent.call(new UserMessage("mastered: bounded wildcard"), context).block();
            List<Msg> before = List.copyOf(agent.getDelegate().getAgentState(context).getContext());
            clearInvocations(stateStore);

            new LearningContextCompactor(agent, model).compact(context);

            List<Msg> after = agent.getDelegate().getAgentState(context).getContext();
            assertThat(after).hasSize(2).isNotEqualTo(before);
            assertThat(after.stream().map(Msg::getTextContent).toList())
                    .anyMatch(text -> text.contains("mastered bounded wildcard"));
            verify(stateStore).save(anyString(), anyString(), anyString(), any(State.class));
        }
    }

    @Test
    void oneOffConfigurationUsesImmediatePublicContract() {
        assertThat(LearningContextCompactor.oneOffConfig().getTriggerMessages()).isEqualTo(1);
        assertThat(LearningContextCompactor.oneOffConfig().getKeepMessages()).isEqualTo(1);
        assertThat(LearningContextCompactor.oneOffConfig().getSummaryPrompt()).contains("{messages}");
    }

    private static final class RecordingModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions generateOptions) {
            String prompt = messages.stream().map(Msg::getTextContent).reduce("", (left, right) -> left + right);
            String response = prompt.contains("Summarize this completed knowledge point")
                    ? "mastered bounded wildcard; next: type erasure"
                    : "ack";
            return Flux.just(ChatResponse.builder()
                    .id("response")
                    .content(List.of(TextBlock.builder().text(response).build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "recording";
        }
    }
}
