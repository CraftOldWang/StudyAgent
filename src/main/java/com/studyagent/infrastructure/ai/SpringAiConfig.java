package com.studyagent.infrastructure.ai;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.studyagent.common.config.AiModelProperties;
import com.studyagent.common.exception.BusinessException;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiConfig {

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingModel embeddingModel(AiModelProperties properties) {
        AiModelProperties.Embedding embedding = requiredEmbedding(properties);
        if (!"dashscope".equalsIgnoreCase(embedding.provider())) {
            throw new BusinessException("不支持的 embedding provider: " + embedding.provider());
        }
        String apiKey = required(embedding.apiKey(), "百炼 DashScope API Key 未配置，请设置 AI_DASHSCOPE_API_KEY");
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .baseUrl(defaultString(embedding.baseUrl(), "https://dashscope.aliyuncs.com"))
                .apiKey(apiKey)
                .build();
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .model(defaultString(embedding.model(), "text-embedding-v3"))
                .dimensions(embedding.dimensions())
                .textType(defaultString(embedding.textType(), "document"))
                .build();
        return new DashScopeEmbeddingModel(
                dashScopeApi,
                MetadataMode.NONE,
                options,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                ObservationRegistry.NOOP
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatModel chatModel(AiModelProperties properties) {
        AiModelProperties.Chat chat = requiredChat(properties);
        if (!"deepseek".equalsIgnoreCase(chat.provider())) {
            throw new BusinessException("不支持的 chat provider: " + chat.provider());
        }
        String apiKey = required(chat.apiKey(), "DeepSeek API Key 未配置，请设置 DEEPSEEK_API_KEY");
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(defaultString(chat.baseUrl(), "https://api.deepseek.com"))
                .apiKey(apiKey)
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(defaultString(chat.model(), "deepseek-chat"))
                .temperature(chat.temperature())
                .maxTokens(chat.maxTokens())
                .build();
        DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of()))
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private AiModelProperties.Embedding requiredEmbedding(AiModelProperties properties) {
        if (properties.embedding() == null) {
            throw new BusinessException("AI embedding 配置不能为空");
        }
        return properties.embedding();
    }

    private AiModelProperties.Chat requiredChat(AiModelProperties properties) {
        if (properties.chat() == null) {
            throw new BusinessException("AI chat 配置不能为空");
        }
        return properties.chat();
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value;
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
