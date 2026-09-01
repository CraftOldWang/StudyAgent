package com.studyagent.infrastructure.ai;

import com.studyagent.common.exception.BusinessException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Spring AI 聊天模型适配器，负责调用模型并校验返回内容。
 */
@Service
public class SpringAiChatGenerationService implements ChatGenerationService {

    private final ChatModel chatModel;
    private final ObjectProvider<ToolCallbackProvider> learningAgentToolCallbackProvider;

    public SpringAiChatGenerationService(
            ChatModel chatModel,
            @Qualifier("learningAgentToolCallbackProvider")
            ObjectProvider<ToolCallbackProvider> learningAgentToolCallbackProvider
    ) {
        this.chatModel = chatModel;
        this.learningAgentToolCallbackProvider = learningAgentToolCallbackProvider;
    }

    /**
     * 调用 ChatModel 生成回答；空响应会转换为明确业务异常。
     */
    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        )));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new BusinessException("模型未返回有效回答");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new BusinessException("模型回答为空");
        }
        return text.trim();
    }

    /**
     * 使用 Spring AI ChatClient 挂载学习 Agent 工具。
     *
     * <p>这里使用 ChatClient 的 toolCallbacks 和 toolContext，而不是在 ChatModel 中全局默认挂载工具。
     * 这样普通聊天不会误触写库工具，只有学习 Agent 的 Planner 明确调用该方法时，模型才具备工具调用能力。</p>
     */
    @Override
    public String plannerWithTools(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> toolContext,
            ToolCallEventListener eventListener
    ) {
        ToolCallbackProvider callbackProvider = learningAgentToolCallbackProvider.getIfAvailable();
        if (callbackProvider == null) {
            throw new BusinessException("学习 Agent 工具未注册，无法执行 tool calling");
        }
        List<ToolCallback> observedCallbacks = Arrays.stream(callbackProvider.getToolCallbacks())
                .map(callback -> new ObservedToolCallback(callback, eventListener))
                .map(ToolCallback.class::cast)
                .toList();
        String text = ChatClient.create(chatModel)
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .toolCallbacks(observedCallbacks)
                .toolContext(toolContext == null ? Map.of() : toolContext)
                .call()
                .content();
        if (text == null || text.isBlank()) {
            throw new BusinessException("模型回答为空");
        }
        return text.trim();
    }

    /**
     * 使用 ChatClient.stream() 输出纯文本 token。
     *
     * <p>这里刻意不挂载 toolCallbacks。Writer 的职责是把 Planner 已确认的决策、工具结果摘要和引用材料
     * 写成自然语言；如果它也能调用工具，就会重新把“决策”和“展示”混到一起，前端仍然可能收到不可展示的结构化片段。</p>
     */
    @Override
    public void streamText(String systemPrompt, String userPrompt, Consumer<String> tokenConsumer) {
        if (tokenConsumer == null) {
            throw new BusinessException("流式 token 消费者不能为空");
        }
        ChatClient.create(chatModel)
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (token != null && !token.isEmpty()) {
                        tokenConsumer.accept(token);
                    }
                })
                .blockLast();
    }
}
