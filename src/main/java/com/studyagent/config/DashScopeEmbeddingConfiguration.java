package com.studyagent.config;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DashScopeEmbeddingConfiguration {

    @Bean
    public TextEmbedding dashScopeTextEmbedding(AiModelProperties properties) {
        return new TextEmbedding(properties.embedding().baseUrl());
    }
}
