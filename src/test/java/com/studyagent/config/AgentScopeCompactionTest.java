package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class AgentScopeCompactionTest {

    private static final String TARGET = "learning target: Java generics";
    private static final String STATUS = "current knowledge-point status: COMPLETED";
    private static final String DISCOVERY =
            "discovery: type erasure removes runtime type parameters; "
                    + "remaining plan: compare bounded wildcards";
    private static final String COMPACTED_SUMMARY =
            "learning target=Java generics; status=COMPLETED; "
                    + "discoveries=type erasure removes runtime type parameters; "
                    + "remaining plan=compare bounded wildcards";

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void compactsAtConfiguredMessageBoundaryAndKeepsLearningFacts(@TempDir Path tempDirectory)
            throws IOException {
        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDirectory.resolve("home").toString());

        RecordingModel model = new RecordingModel();
        AgentScopeAgentConfiguration configuration = new AgentScopeAgentConfiguration();
        AgentScopeModelProperties modelProperties = new AgentScopeModelProperties(
                null,
                null,
                1,
                null,
                null);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId("user-1")
                .sessionId("learning-session-1")
                .build();
        Path workspace = Files.createDirectories(tempDirectory.resolve("workspace"));
        CompactionConfig compactionConfig = configuration.learningCompactionConfig();

        assertThat(compactionConfig.getTriggerMessages()).isEqualTo(
                AgentScopeAgentConfiguration.COMPACTION_TRIGGER_MESSAGES);
        assertThat(compactionConfig.getKeepMessages()).isEqualTo(
                AgentScopeAgentConfiguration.COMPACTION_KEEP_MESSAGES);
        assertThat(compactionConfig.getSummaryPrompt()).contains("{messages}");

        try (HarnessAgent agent = configuration.harnessAgent(
                model,
                workspace,
                modelProperties)) {
            call(agent, runtimeContext, TARGET);
            call(agent, runtimeContext, STATUS);
            call(agent, runtimeContext, DISCOVERY);
            call(agent, runtimeContext, "knowledge point completion recorded");

            assertThat(model.summaryPrompts()).hasSize(1);
            assertThat(model.summaryPrompts().getFirst())
                    .contains(TARGET, STATUS, DISCOVERY);

            List<Msg> context = agent.getDelegate().getAgentState(runtimeContext).getContext();
            assertThat(context).hasSizeLessThan(8);
            assertThat(context.stream().map(Msg::getTextContent).collect(Collectors.joining("\n")))
                    .contains(COMPACTED_SUMMARY);
            assertThat(agent.getCompactionHook()).isNotNull();
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private void call(HarnessAgent agent, RuntimeContext runtimeContext, String message) {
        agent.call(new UserMessage(message), runtimeContext)
                .block(Duration.ofSeconds(10));
    }

    private static final class RecordingModel implements Model {

        private final List<String> summaryPrompts = new ArrayList<>();
        private final AtomicInteger responseSequence = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions generateOptions) {
            String prompt = messages.stream()
                    .map(Msg::getTextContent)
                    .collect(Collectors.joining("\n"));
            if (prompt.contains("You maintain the compacted context for a learning assistant.")) {
                summaryPrompts.add(prompt);
                return Flux.just(response(COMPACTED_SUMMARY));
            }
            return Flux.just(response("ack"));
        }

        @Override
        public String getModelName() {
            return "recording-compaction-model";
        }

        List<String> summaryPrompts() {
            return summaryPrompts;
        }

        private ChatResponse response(String text) {
            return ChatResponse.builder()
                    .id("response-" + responseSequence.incrementAndGet())
                    .content(List.of(TextBlock.builder().text(text).build()))
                    .finishReason("stop")
                    .build();
        }
    }
}
