package com.studyagent.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "study-agent.agentscope")
public record AgentScopeWorkspaceProperties(Path workspace) {
}
