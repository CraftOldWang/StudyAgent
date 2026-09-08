package com.studyagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.ObservedModel;
import com.studyagent.eval.ModelUsageRecorder;
import java.io.IOException;
import java.util.Map;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.GenerateOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentScopeModelProperties.class, ModelUsageProperties.class})
public class AgentScopeModelConfiguration {

    public static final String PRIMARY_MODEL_BEAN_NAME = "agentScopePrimaryModel";

    @Bean(PRIMARY_MODEL_BEAN_NAME)
    public Model agentScopePrimaryModel(AgentScopeModelProperties properties, ModelUsageRecorder recorder) {
        return new ObservedModel(resolve(properties.primaryModelId(), properties), recorder);
    }

    @Bean
    public ModelUsageRecorder modelUsageRecorder(ModelUsageProperties properties, ObjectMapper mapper)
            throws IOException {
        return new ModelUsageRecorder(properties, mapper);
    }

    static Model resolve(String modelId, AgentScopeModelProperties properties) {
        return ModelRegistry.resolve(modelId, creationContext(modelId, properties));
    }

    static ModelCreationContext creationContext(
            String modelId,
            AgentScopeModelProperties properties) {
        AgentScopeModelProperties.Provider provider = providerFor(modelId, properties);
        ModelCreationContext.Builder builder = ModelCreationContext.builder()
                .apiKey(provider.apiKey())
                .baseUrl(provider.baseUrl());
        if (provider.maxTokens() != null || provider.thinkingEnabled() != null || provider.temperature() != null) {
            GenerateOptions.Builder options = GenerateOptions.builder()
                    .maxTokens(provider.maxTokens()).temperature(provider.temperature());
            if (provider.thinkingEnabled() != null) {
                options.additionalBodyParam("thinking",
                        Map.of("type", provider.thinkingEnabled() ? "enabled" : "disabled"));
            }
            builder.component(
                    GenerateOptions.class,
                    options.build());
        }
        return builder.build();
    }

    private static AgentScopeModelProperties.Provider providerFor(
            String modelId,
            AgentScopeModelProperties properties) {
        if (modelId != null && modelId.startsWith("dashscope:")) {
            return properties.dashscope();
        }
        if (modelId != null && modelId.startsWith("deepseek:")) {
            return properties.deepseek();
        }
        throw new IllegalArgumentException("Unsupported AgentScope model id: " + modelId);
    }
}
