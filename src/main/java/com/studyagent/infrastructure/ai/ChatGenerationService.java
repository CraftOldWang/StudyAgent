package com.studyagent.infrastructure.ai;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 聊天生成服务接口，业务模块通过它调用模型而不依赖具体 provider。
 */
public interface ChatGenerationService {

    /**
     * 根据 system prompt 和 user prompt 生成完整回答文本。
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * Agent Planner 专用的非流式工具调用入口。
     *
     * <p>工具上下文由后端传入，包含 userId、sessionId、知识库授权范围等安全敏感信息；
     * 模型只能看到工具 schema 和自己填写的业务参数，不能决定资源范围。这个方法只给 Planner 使用，
     * Response Writer 必须走 {@link #streamText(String, String, Consumer)}，避免用户可见文本混入工具 JSON。</p>
     */
    String plannerWithTools(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> toolContext,
            ToolCallEventListener eventListener
    );

    /**
     * 纯文本流式输出入口，不挂载任何工具。
     *
     * <p>调用方负责把 token 转成 SSE，并且只有完整成功后才能落库 assistant 消息。这样即使流式输出中途失败，
     * 已经推给前端的 token 也不会被误写成一条可恢复的历史消息。</p>
     */
    void streamText(String systemPrompt, String userPrompt, Consumer<String> tokenConsumer);

    /**
     * 兼容旧调用点的命名，后续代码统一改用 plannerWithTools。
     */
    @Deprecated
    default String generateWithLearningTools(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> toolContext,
            ToolCallEventListener eventListener
    ) {
        return plannerWithTools(systemPrompt, userPrompt, toolContext, eventListener);
    }
}
