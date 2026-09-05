package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.studyagent.agent.integration.KnowledgeSearchTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class KnowledgeSearchAgentConfigurationTest {

    @Test
    void buildsAgentWhoseToolkitExposesOnlyKnowledgeSearch() {
        KnowledgeSearchTool tool = mock(KnowledgeSearchTool.class);
        when(tool.getName()).thenReturn(KnowledgeSearchTool.TOOL_NAME);
        KnowledgeSearchAgentConfiguration configuration = new KnowledgeSearchAgentConfiguration();
        Toolkit toolkit = configuration.knowledgeSearchToolkit(tool);

        ReActAgent agent = configuration.knowledgeSearchAgent(
                new NoCallModel(),
                properties(),
                toolkit);
        try {
            assertThat(agent.getToolkit().getToolNames())
                    .containsExactly(KnowledgeSearchTool.TOOL_NAME);
            assertThat(agent.getSysPrompt())
                    .contains("必须调用 knowledge_search")
                    .contains("不得声称看过整份原始文件");
        } finally {
            agent.close();
        }
    }

    private AgentScopeModelProperties properties() {
        AgentScopeModelProperties.Provider provider =
                new AgentScopeModelProperties.Provider("test-key", "https://example.com", 1800);
        return new AgentScopeModelProperties(
                "deepseek:deepseek-chat", null, 1, provider, provider);
    }

    private record NoCallModel() implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions generateOptions) {
            return Flux.error(new AssertionError("configuration test must not call a model"));
        }

        @Override
        public String getModelName() {
            return "test-model";
        }
    }
}
