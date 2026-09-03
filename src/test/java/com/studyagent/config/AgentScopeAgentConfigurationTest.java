package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class AgentScopeAgentConfigurationTest {

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void buildsHarnessAgentFromQualifiedDependenciesWithoutFallback(@TempDir Path workspace) {
        Model primaryModel = new NoCallModel("primary-model");
        Model unrelatedModel = new NoCallModel("unrelated-model");
        Path unrelatedPath = workspace.resolve("unrelated");
        String previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", workspace.resolve("home").toString());

        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(AgentScopeAgentConfiguration.class)
                    .withPropertyValues("study-agent.agentscope.model.max-retries=1")
                    .withBean(
                            AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME,
                            Model.class,
                            () -> primaryModel)
                    .withBean("unrelatedModel", Model.class, () -> unrelatedModel)
                    .withBean(
                            AgentScopeWorkspaceConfiguration.WORKSPACE_PATH_BEAN_NAME,
                            Path.class,
                            () -> workspace)
                    .withBean("unrelatedPath", Path.class, () -> unrelatedPath)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(HarnessAgent.class);

                        HarnessAgent agent = context.getBean(
                                AgentScopeAgentConfiguration.HARNESS_AGENT_BEAN_NAME,
                                HarnessAgent.class);

                        try {
                            assertThat(agent.getModel()).isSameAs(primaryModel);
                            assertThat(agent.getWorkspaceManager().getWorkspace()).isEqualTo(workspace);
                            assertThat(agent.getDelegate().getModelConfig().maxRetries()).isEqualTo(1);
                            assertThat(agent.getDelegate().getModelConfig().fallbackModel()).isNull();
                        } finally {
                            agent.close();
                        }
                    });
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private record NoCallModel(String modelName) implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions generateOptions) {
            return Flux.error(new AssertionError(
                    "HarnessAgent configuration test must not call a remote model"));
        }

        @Override
        public String getModelName() {
            return modelName;
        }
    }
}
