package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.learning.KnowledgePointStatus;
import com.studyagent.learning.LearningStateTransitionTool;
import com.studyagent.learning.LearningTransitionIntent;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class LearningRuntimeToolChoiceTest {
    @Test
    void runtimeForwardsTransitionChoiceThenRestoresAutomaticChoice(@TempDir Path workspace) {
        RuntimeContext context = RuntimeContext.builder().userId("1").sessionId("runtime-test").build();
        context.put(KnowledgeSearchExecution.class, new KnowledgeSearchExecution(
                new KnowledgeSearchResponse("definition", null, List.of(
                        new KnowledgeSearchResponse.Result("chunk-1", "definition", null, 1.0)))));
        LearningTransitionIntent intent = new LearningTransitionIntent(
                KnowledgePointStatus.NEW, KnowledgePointStatus.EXPLAINING);
        context.put(LearningTransitionIntent.class, intent);
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            @Override public String getModelName() { return "runtime-test"; }
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                    GenerateOptions options) {
                if (calls.getAndIncrement() == 0) {
                    assertThat(options.getToolChoice()).isEqualTo(
                            new ToolChoice.Specific(LearningStateTransitionTool.TOOL_NAME));
                    assertThat(tools).extracting(ToolSchema::getName)
                            .contains(LearningStateTransitionTool.TOOL_NAME);
                    return Flux.just(ChatResponse.builder().id("transition").content(List.of(
                            ToolUseBlock.builder().id("tool-1").name(LearningStateTransitionTool.TOOL_NAME)
                                    .input(Map.of("target", "EXPLAINING"))
                                    .content("{\"target\":\"EXPLAINING\"}").build()))
                            .finishReason("tool_calls").build());
                }
                String toolResult = messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .flatMap(result -> result.getOutput().stream())
                        .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                        .map(TextBlock::getText).reduce("", (a, b) -> a + b);
                assertThat(options.getToolChoice()).as("tool result: %s", toolResult).isNull();
                return Flux.just(ChatResponse.builder().id("answer")
                        .content(List.of(TextBlock.builder().text("Definition [chunk-1]").build()))
                        .finishReason("stop").build());
            }
        };
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new LearningStateTransitionTool());
        try (var agent = new AgentScopeAgentConfiguration().harnessAgent(model, workspace,
                new AgentScopeModelProperties(null, null, 1, null, null), toolkit)) {
            agent.call("Explain the definition", context).block(Duration.ofSeconds(10));
            intent.requireRequested();
            assertThat(calls.get()).isEqualTo(2);
        }
    }
}
