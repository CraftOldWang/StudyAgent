package com.studyagent.modules.learning.application;

import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.learning.domain.ChatSession;
import com.studyagent.modules.learning.domain.LearningTodo;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent Planner：非流式、可调用工具、只输出结构化决策。
 *
 * <p>Planner 的输出不会直接展示给用户。它负责决定当前 phase、是否需要检索/写卡、当前 Todo 是否完成以及
 * Writer 应该怎样组织自然语言回复。后端拿到 decision 后仍会再次校验，再决定是否推进有限状态机。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentPlannerService {

    private final ChatGenerationService chatGenerationService;
    private final AgentPlannerJsonCodec plannerJsonCodec;

    /**
     * 执行一次 Planner 调用。JSON 解析失败时允许一次 repair retry，但 retry 仍失败就把错误交给上层记录失败状态。
     */
    public PlannerResult decide(PlannerRequest request) {
        String rawOutput = chatGenerationService.plannerWithTools(
                plannerSystemPrompt(),
                plannerUserPrompt(request),
                request.toolContext(),
                request.toolTrace()
        );
        try {
            return new PlannerResult(plannerJsonCodec.parseDecision(rawOutput), rawOutput, "", false);
        } catch (RuntimeException parseFailure) {
            String repairedOutput = chatGenerationService.generate(
                    repairSystemPrompt(),
                    repairUserPrompt(rawOutput, parseFailure.getMessage())
            );
            AgentPlannerDecision repairedDecision = plannerJsonCodec.parseDecision(repairedOutput);
            return new PlannerResult(repairedDecision, rawOutput, repairedOutput, true);
        }
    }

    private String plannerSystemPrompt() {
        return """
                你是学习 Agent 的 Planner。
                你负责工具调用、状态判断和结构化决策，不负责生成给用户看的长回复。
                你可以按需调用 knowledge_search 和 review_card_write。
                你必须只输出一个 JSON 对象，不要输出 Markdown、解释或代码块。
                JSON 格式必须是：
                {
                  "phase": "TEACH|QA|QUIZ|SUMMARY",
                  "currentTopicStatus": "IN_PROGRESS|NEEDS_USER_INPUT|DONE",
                  "nextAction": "WAIT_USER|CONTINUE_TOPIC|COMPLETE_TOPIC|MOVE_NEXT_TOPIC|FINISH_SESSION",
                  "responsePlan": "给 Response Writer 的写作计划，不直接展示给用户",
                  "summary": "只有 currentTopicStatus=DONE 时必填，用于 topic summary 和 context snapshot",
                  "reason": "给后端审计/调试看的简短原因"
                }

                决策规则：
                1. 只处理 currentTodo，不要主动跳到其他 Todo 的实质内容。
                2. phase 只能是 TEACH、QA、QUIZ、SUMMARY；不要输出 CARD。
                3. 复习卡写入不是 phase，只能通过 review_card_write 工具按需完成。
                4. 用户要求“生成复习卡、记住这个、加入复习”时，优先调用 review_card_write；工具失败时不要假装成功。
                5. 需要教材、笔记、文档依据时调用 knowledge_search；用户身份、会话和知识库范围由服务端控制。
                6. knowledge_search 无召回时，responsePlan 中要明确让 Writer 说明“知识库未检索到相关内容”。
                7. 当用户刚开始学习当前知识点，通常 phase=TEACH，讲完后 WAIT_USER 或 CONTINUE_TOPIC。
                8. 用户追问或表示没懂时，通常 phase=QA，回答后 WAIT_USER。
                9. 用户要求练习、测验或巩固时，通常 phase=QUIZ。
                10. 只有当前知识点已经讲清、答疑/练习足够，并形成可压缩总结时，才能 currentTopicStatus=DONE。
                11. currentTopicStatus=DONE 时 summary 必填；未 DONE 时不要执行完成类 nextAction。
                """;
    }

    private String plannerUserPrompt(PlannerRequest request) {
        return """
                学习目标：
                %s

                当前会话 ID：%d
                当前知识点：
                id=%d
                title=%s
                description=%s
                status=%s

                Todo 列表：
                %s

                可恢复上下文：
                %s

                用户最新消息：
                %s
                """.formatted(
                request.learningGoal(),
                request.session().getId(),
                request.currentTodo().getId(),
                request.currentTodo().getTitle(),
                request.currentTodo().getDescription(),
                request.currentTodo().getStatus(),
                request.todoListText(),
                request.contextText(),
                request.userMessage()
        );
    }

    private String repairSystemPrompt() {
        return """
                你是 Planner JSON 修复器。
                只把输入修复成指定 JSON schema，不要补充解释、Markdown 或代码块。
                不要改变原始语义；如果原始内容缺字段，请用最保守且符合规则的值。
                """ + "\n" + plannerJsonCodec.decisionSchemaText();
    }

    private String repairUserPrompt(String rawOutput, String errorMessage) {
        return """
                原始 Planner 输出：
                %s

                解析/校验错误：
                %s

                请修复为合法 JSON。
                """.formatted(rawOutput, errorMessage == null ? "" : errorMessage);
    }

    /**
     * Planner 输入上下文。这里保留 domain 对象，是为了让 prompt 构造和业务含义贴近应用层，
     * 但安全敏感的 toolContext 仍由上层从后端会话状态构造，模型不能自行决定资源范围。
     */
    public record PlannerRequest(
            ChatSession session,
            LearningTodo currentTodo,
            String learningGoal,
            String userMessage,
            String contextText,
            String todoListText,
            List<Long> knowledgeBaseIds,
            Map<String, Object> toolContext,
            ToolCallTraceCollector.Trace toolTrace
    ) {
    }

    /**
     * Planner 调用结果，保留 rawOutput/repairOutput 方便 agent_step_records 回放和排障。
     */
    public record PlannerResult(
            AgentPlannerDecision decision,
            String rawOutput,
            String repairedOutput,
            boolean repairAttempted
    ) {
    }
}
