package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class AgentScopeModelConfigurationTest {

    private static final String DASHSCOPE_MODEL_ID = "dashscope:qwen-plus";
    private static final String DEEPSEEK_MODEL_ID = "deepseek:deepseek-chat";
    private static final HttpTransport NO_REQUEST_TRANSPORT = new HttpTransport() {
        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new AssertionError("Model configuration test must not execute HTTP requests");
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            return Flux.error(new AssertionError(
                    "Model configuration test must not stream HTTP requests"));
        }

        @Override
        public void close() {
            // The test transport owns no resources.
        }
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentScopeModelConfiguration.class)
            .withPropertyValues(
                    "study-agent.agentscope.model.primary-model-id=" + DEEPSEEK_MODEL_ID,
                    "study-agent.agentscope.model.max-retries=1",
                    "study-agent.agentscope.model.dashscope.api-key=test-dashscope-key",
                    "study-agent.agentscope.model.dashscope.base-url=https://dashscope.example.test",
                    "study-agent.agentscope.model.deepseek.api-key=test-deepseek-key",
                    "study-agent.agentscope.model.deepseek.base-url=https://deepseek.example.test");

    @BeforeEach
    void resetModelRegistryBeforeTest() {
        ModelRegistry.reset();
        HttpTransportFactory.setDefault(NO_REQUEST_TRANSPORT);
    }

    @AfterEach
    void resetModelRegistryAfterTest() {
        try {
            ModelRegistry.reset();
        } finally {
            HttpTransportFactory.setDefault(null);
            HttpTransportFactory.unregister(NO_REQUEST_TRANSPORT);
        }
    }

    @Test
    void resolvesNativeProvidersAndExposesOnlyTheSelectedPrimaryModel() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            AgentScopeModelProperties properties = context.getBean(AgentScopeModelProperties.class);
            assertThat(ModelRegistry.canResolve(DASHSCOPE_MODEL_ID)).isTrue();
            assertThat(ModelRegistry.canResolve(DEEPSEEK_MODEL_ID)).isTrue();
            assertThat(AgentScopeModelConfiguration.resolve(DASHSCOPE_MODEL_ID, properties).getModelName())
                    .isEqualTo("qwen-plus");
            assertThat(AgentScopeModelConfiguration.resolve(DEEPSEEK_MODEL_ID, properties).getModelName())
                    .isEqualTo("deepseek-chat");

            Model primaryModel = context.getBean(
                    AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME,
                    Model.class);
            assertThat(primaryModel.getModelName()).isEqualTo("deepseek-chat");
            assertThat(context).hasSingleBean(Model.class);
            assertThat(properties.primaryModelId()).isEqualTo(DEEPSEEK_MODEL_ID);
            assertThat(properties.fallbackModelId()).isNull();
            assertThat(properties.maxRetries()).isEqualTo(1);
        });
    }
}
