package com.studyagent.config;

import com.studyagent.agent.integration.KnowledgeSearchTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KnowledgeSearchAgentConfiguration {

    public static final String AGENT_BEAN_NAME = "knowledgeSearchAgent";
    public static final String TOOLKIT_BEAN_NAME = "knowledgeSearchToolkit";

    private static final String SYSTEM_PROMPT = """
            你是 StudyAgent 的知识库问答助手。回答前必须调用 knowledge_search，且只能使用该工具返回的
            当前知识库片段作为事实依据。不得调用其他工具，不得声称看过整份原始文件，不得补造来源。
            如果工具返回没有资料依据，直接明确说明当前知识库没有可支持该问题的资料依据。
            """;

    @Bean(TOOLKIT_BEAN_NAME)
    public Toolkit knowledgeSearchToolkit(KnowledgeSearchTool knowledgeSearchTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(knowledgeSearchTool);
        return toolkit;
    }

    @Bean(AGENT_BEAN_NAME)
    public ReActAgent knowledgeSearchAgent(
            @Qualifier(AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME) Model model,
            AgentScopeModelProperties modelProperties,
            @Qualifier(TOOLKIT_BEAN_NAME) Toolkit toolkit) {
        return ReActAgent.builder()
                .name("knowledge-search-agent")
                .description("只使用当前知识库检索片段回答问题")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .maxIters(4)
                .maxRetries(modelProperties.maxRetries())
                .build();
    }
}
