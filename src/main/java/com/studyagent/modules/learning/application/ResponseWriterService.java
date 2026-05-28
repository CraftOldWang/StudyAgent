package com.studyagent.modules.learning.application;

import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.learning.domain.ChatSession;
import com.studyagent.modules.learning.domain.LearningTodo;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Response Writer：流式、无工具、只生成用户可见自然语言。
 *
 * <p>Writer 的输入来自已经通过后端校验的 Planner decision，以及工具 trace 中整理出的摘要。它不能输出 JSON，
 * 也不能再调用工具，因此 token.delta 可以安全地直接推给前端作为用户可读文本。</p>
 */
@Service
@RequiredArgsConstructor
public class ResponseWriterService {

    private final ChatGenerationService chatGenerationService;

    /**
     * 生成纯文本流，并把每个 token 交给上层转换成 SSE。
     */
    public void stream(WriterRequest request, Consumer<String> tokenConsumer) {
        chatGenerationService.streamText(writerSystemPrompt(), writerUserPrompt(request), tokenConsumer);
    }

    private String writerSystemPrompt() {
        return """
                你是学习助手的用户回复生成器。
                你只输出自然语言正文。
                不要输出 JSON。
                不要输出状态字段。
                不要声称调用了未发生的工具。
                如果 planner 提到知识库无召回，明确说明“知识库未检索到相关内容”。
                如果 review_card_write 已成功调用，可以说明已写入复习卡。
                你的回复要面向学习者，清晰、分段、可直接阅读。
                """;
    }

    private String writerUserPrompt(WriterRequest request) {
        return """
                学习目标：
                %s

                当前会话 ID：
                %d

                当前 Todo：
                title=%s
                description=%s
                status=%s

                用户最新消息：
                %s

                最近上下文：
                %s

                Planner decision：
                phase=%s
                currentTopicStatus=%s
                nextAction=%s
                responsePlan=%s
                summary=%s
                reason=%s

                工具调用结果摘要：
                %s

                可引用资料摘要：
                %s
                """.formatted(
                request.learningGoal(),
                request.session().getId(),
                request.currentTodo().getTitle(),
                request.currentTodo().getDescription(),
                request.currentTodo().getStatus(),
                request.userMessage(),
                request.contextText(),
                request.decision().phase(),
                request.decision().currentTopicStatus(),
                request.decision().nextAction(),
                request.decision().responsePlan(),
                request.decision().summary(),
                request.decision().reason(),
                request.toolSummaryText(),
                request.referenceSummaryText()
        );
    }

    public record WriterRequest(
            ChatSession session,
            LearningTodo currentTodo,
            String learningGoal,
            String userMessage,
            String contextText,
            AgentPlannerDecision decision,
            String toolSummaryText,
            String referenceSummaryText
    ) {
    }
}
