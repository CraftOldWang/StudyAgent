package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentScopeWorkspaceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentScopeWorkspaceConfiguration.class);

    @Test
    void bindsOverrideCreatesDirectoryAndExposesNamedPath(
            @TempDir(factory = TargetDirectoryTempDirFactory.class) Path tempDirectory) {
        Path configuredWorkspace = tempDirectory.resolve("custom-workspace");
        assertThat(Files.notExists(configuredWorkspace)).isTrue();

        contextRunner
                .withPropertyValues("study-agent.agentscope.workspace=" + configuredWorkspace)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgentScopeWorkspaceProperties.class);

                    AgentScopeWorkspaceProperties properties = context.getBean(AgentScopeWorkspaceProperties.class);
                    Path workspace = context.getBean(
                            AgentScopeWorkspaceConfiguration.WORKSPACE_PATH_BEAN_NAME,
                            Path.class);

                    assertThat(properties.workspace()).isEqualTo(configuredWorkspace);
                    assertThat(workspace).isEqualTo(configuredWorkspace);
                    assertThat(Files.isDirectory(workspace)).isTrue();
                    assertThat(HarnessAgent.builder().workspace(workspace)).isNotNull();
                });
    }

    public static class TargetDirectoryTempDirFactory implements TempDirFactory {

        @Override
        public Path createTempDirectory(
                AnnotatedElementContext elementContext,
                ExtensionContext extensionContext) throws IOException {
            return Files.createTempDirectory(Path.of("target"), "agentscope-workspace-");
        }
    }
}
