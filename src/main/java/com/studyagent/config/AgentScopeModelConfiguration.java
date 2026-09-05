package com.studyagent.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.GenerateOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentScopeModelProperties.class)
public class AgentScopeModelConfiguration {

    public static final String PRIMARY_MODEL_BEAN_NAME = "agentScopePrimaryModel";

    @Bean(PRIMARY_MODEL_BEAN_NAME)
    public Model agentScopePrimaryModel(AgentScopeModelProperties properties) {
        return resolve(properties.primaryModelId(), properties);
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
        if (provider.maxTokens() != null) {
            builder.component(
                    GenerateOptions.class,
                    GenerateOptions.builder().maxTokens(provider.maxTokens()).build());
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
