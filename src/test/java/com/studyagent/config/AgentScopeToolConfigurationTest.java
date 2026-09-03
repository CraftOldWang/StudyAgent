package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentScopeToolConfigurationTest {

    @Test
    void registersAgentToolsThroughTheNativeToolkit() throws IOException {
        Path workspace = Files.createDirectories(
                Path.of("target", "agentscope-tool-configuration-test"));
        AgentTool search = tool("knowledge_search");
        AgentTool write = tool("review_card_write");

        new ApplicationContextRunner()
                .withUserConfiguration(AgentScopeAgentConfiguration.class)
                .withPropertyValues("study-agent.agentscope.model.max-retries=1")
                .withBean(
                        AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME,
                        Model.class,
                        () -> new NoCallModel())
                .withBean(
                        AgentScopeWorkspaceConfiguration.WORKSPACE_PATH_BEAN_NAME,
                        Path.class,
                        () -> workspace)
                .withBean("knowledgeSearchTool", AgentTool.class, () -> search)
                .withBean("reviewCardWriteTool", AgentTool.class, () -> write)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HarnessAgent agent = context.getBean(
                            AgentScopeAgentConfiguration.HARNESS_AGENT_BEAN_NAME,
                            HarnessAgent.class);
                    try {
                        assertThat(agent.getToolkit().getToolNames())
                                .contains("knowledge_search", "review_card_write");
                    } finally {
                        agent.close();
                    }
                });
    }

    private AgentTool tool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn(name);
        when(tool.getParameters()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false));
        when(tool.callAsync(org.mockito.ArgumentMatchers.any(ToolCallParam.class)))
                .thenReturn(Mono.just(ToolResultBlock.text("ok")));
        return tool;
    }

    private record NoCallModel() implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions generateOptions) {
            return Flux.error(new AssertionError(
                    "tool configuration test must not call a remote model"));
        }

        @Override
        public String getModelName() {
            return "test-model";
        }
    }
}
