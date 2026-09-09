package com.studyagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.KnowledgeSearchTool;
import com.studyagent.learning.LearningActionTool;
import com.studyagent.learning.LearningTurnIntent.Action;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LearningConversationProperties.class)
public class LearningConversationConfiguration {
    @org.springframework.context.annotation.Bean(destroyMethod = "close")
    public java.util.concurrent.ExecutorService learningConversationExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    // A toolkit belongs to one invocation; active tool groups must not leak between learning sessions.
    public static Toolkit toolkit(KnowledgeSearchTool search, ObjectMapper mapper,
            java.util.function.UnaryOperator<io.agentscope.core.tool.AgentTool> decorate) {
        Toolkit toolkit = new Toolkit();
        java.util.function.Consumer<io.agentscope.core.tool.AgentTool> register = tool -> toolkit.registerAgentTool(decorate.apply(tool));
        register.accept(search);
        register.accept(new LearningActionTool("learning_explanation_done",
                "用户要求开始且当前NEW时，先检索、输出完整讲解，再必须调用本工具提交讲解完成并请求NEW→EXPLAINING；漏调会导致讲解未保存且不能进入测验。普通追问不调用。",
                object(Map.of(), List.of()), Action.EXPLANATION, mapper));
        register.accept(new LearningActionTool("learning_quiz_publish",
                "用户希望测验且当前为EXPLAINING时，先检索，再一次提交五道四选一题。correctAnswer必须原样复制某个完整选项文本，不填A/B/C/D位置编号。正确答案仅交给此工具，不在普通回复透露。成功后结束本轮。",
                object(Map.of("questions", Map.of("type", "array", "minItems", 5, "maxItems", 5, "items", object(
                        Map.of("question", text(), "options", Map.of("type", "array", "minItems", 4, "maxItems", 4, "items", text()),
                                "correctAnswer", Map.of("type", "string", "description", "原样复制options数组中正确选项的完整字符串，不使用选项位置字母或数字。"), "explanation", text(), "sourceChunkId", text()),
                        List.of("question", "options", "correctAnswer", "explanation", "sourceChunkId")))), List.of("questions")), Action.QUIZ, mapper));
        register.accept(new LearningActionTool("learning_quiz_submit",
                "用户明确完整提交五题选择答案且当前为QUIZZING时调用。服务端读取原始用户消息中的1.A等编号答案，确定性评分；模型不能代填或改分。成功后结束本轮。",
                object(Map.of(), List.of()), Action.GRADE, mapper));
        register.accept(new LearningActionTool("learning_cards_publish",
                "用户希望生成复习卡且当前为CARD_GENERATING时，先检索，再一次提交三张带来源的卡片。服务端会保存、摘要并完成知识点；成功后结束本轮。",
                object(Map.of("cards", Map.of("type", "array", "minItems", 3, "maxItems", 3, "items", object(
                        Map.of("front", text(), "back", text(), "sourceChunkId", text()), List.of("front", "back", "sourceChunkId")))),
                        List.of("cards")), Action.CARDS, mapper));
        return toolkit;
    }
    private static Map<String, Object> text() { return Map.of("type", "string"); }
    private static Map<String, Object> object(Map<String, Object> fields, List<String> required) {
        return Map.of("type", "object", "properties", fields, "required", required, "additionalProperties", false);
    }
}
