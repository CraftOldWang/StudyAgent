package com.studyagent.config;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentScopeModelProperties.class)
public class AgentScopeAgentConfiguration {

    public static final String HARNESS_AGENT_BEAN_NAME = "harnessAgent";
    static final int COMPACTION_TRIGGER_MESSAGES = 6;
    static final int COMPACTION_KEEP_MESSAGES = 2;
    static final String COMPACTION_SUMMARY_PROMPT = """
            You maintain the compacted context for a learning assistant.
            Extract only facts needed to continue the same learning session.
            Preserve these labeled fields exactly when they are present:
            - learning target
            - current knowledge-point status
            - discoveries and misconceptions
            - remaining plan
            Do not invent facts, and do not include credentials or permissions.
            Return a concise labeled summary with those four fields.
            Conversation history:
            <messages>
            {messages}
            </messages>
            """;

    @Bean(HARNESS_AGENT_BEAN_NAME)
    public HarnessAgent harnessAgent(
            @Qualifier(AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME) Model model,
            @Qualifier(AgentScopeWorkspaceConfiguration.WORKSPACE_PATH_BEAN_NAME) Path workspace,
            AgentScopeModelProperties modelProperties) {
        return HarnessAgent.builder()
                .model(model)
                .workspace(workspace)
                .maxRetries(modelProperties.maxRetries())
                .compaction(learningCompactionConfig())
                .disableSubagents()
                .build();
    }

    static CompactionConfig learningCompactionConfig() {
        return CompactionConfig.builder()
                .triggerMessages(COMPACTION_TRIGGER_MESSAGES)
                .keepMessages(COMPACTION_KEEP_MESSAGES)
                .summaryPrompt(COMPACTION_SUMMARY_PROMPT)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .build();
    }
}
