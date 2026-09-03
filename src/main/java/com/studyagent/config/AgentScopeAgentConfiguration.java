package com.studyagent.config;

import com.studyagent.agent.governance.ToolGovernanceInterceptor;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentScopeModelProperties.class)
public class AgentScopeAgentConfiguration {

    public static final String HARNESS_AGENT_BEAN_NAME = "harnessAgent";
    public static final String TOOLKIT_BEAN_NAME = "agentScopeToolkit";

    @Bean(TOOLKIT_BEAN_NAME)
    public Toolkit agentScopeToolkit(ObjectProvider<AgentTool> agentTools) {
        Toolkit toolkit = new Toolkit();
        agentTools.orderedStream().forEach(toolkit::registerAgentTool);
        return toolkit;
    }

    @Bean(HARNESS_AGENT_BEAN_NAME)
    public HarnessAgent harnessAgent(
            @Qualifier(AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME) Model model,
            @Qualifier(AgentScopeWorkspaceConfiguration.WORKSPACE_PATH_BEAN_NAME) Path workspace,
            AgentScopeModelProperties modelProperties,
            @Qualifier(TOOLKIT_BEAN_NAME) Toolkit toolkit) {
        return HarnessAgent.builder()
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .maxRetries(modelProperties.maxRetries())
                .middleware(new ToolGovernanceInterceptor())
                .disableSubagents()
                .build();
    }
}
