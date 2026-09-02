package com.studyagent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentScopeWorkspaceProperties.class)
public class AgentScopeWorkspaceConfiguration {

    public static final String WORKSPACE_PATH_BEAN_NAME = "agentScopeWorkspacePath";

    @Bean(WORKSPACE_PATH_BEAN_NAME)
    public Path agentScopeWorkspacePath(AgentScopeWorkspaceProperties properties) throws IOException {
        return Files.createDirectories(properties.workspace());
    }
}
