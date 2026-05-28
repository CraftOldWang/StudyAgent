package com.studyagent.modules.tool.application;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 学习 Agent 的 Spring AI 工具注册配置。
 */
@Configuration
public class LearningAgentToolConfig {

    /**
     * 将 @Tool 标注的方法转换成 Spring AI ToolCallbackProvider。
     *
     * <p>后续 Agent 调用 ChatClient 时可以通过 toolCallbacks(provider) 或 tools(springAiLearningAgentTools)
     * 挂载这两个工具。这里不做全局默认挂载，避免普通聊天模型调用误触写库工具。</p>
     */
    @Bean
    public ToolCallbackProvider learningAgentToolCallbackProvider(SpringAiLearningAgentTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
