package com.studyagent.config;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentScopeModelProperties.class)
public class AgentScopeAgentConfiguration {

    public static final String HARNESS_AGENT_BEAN_NAME = "harnessAgent";

    @Bean(HARNESS_AGENT_BEAN_NAME)
    public HarnessAgent harnessAgent(
            @Qualifier(AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME) Model model,
            @Qualifier(AgentScopeWorkspaceConfiguration.WORKSPACE_PATH_BEAN_NAME) Path workspace,
            AgentScopeModelProperties modelProperties) {
        return HarnessAgent.builder()
                .model(model)
                .workspace(workspace)
                .maxRetries(modelProperties.maxRetries())
                .disableSubagents()
                .build();
    }
}
