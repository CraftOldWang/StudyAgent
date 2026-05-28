package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.learning.domain.AgentRun;
import com.studyagent.modules.learning.domain.AgentStepRecord;
import com.studyagent.modules.learning.domain.ChatMessage;
import com.studyagent.modules.learning.domain.ChatSession;
import com.studyagent.modules.learning.infrastructure.AgentRunMapper;
import com.studyagent.modules.learning.infrastructure.AgentStepRecordMapper;
import com.studyagent.modules.learning.infrastructure.ChatMessageMapper;
import com.studyagent.modules.learning.infrastructure.ChatSessionMapper;
import com.studyagent.modules.learning.interfaces.LearningSessionResponse;
import com.studyagent.modules.learning.interfaces.QuizQuestionResponse;
import com.studyagent.modules.rag.domain.RagReference;
import com.studyagent.modules.rag.domain.RagSearchResult;
import com.studyagent.modules.review.interfaces.ReviewCardResponse;
import com.studyagent.modules.tool.application.KnowledgeSearchTool;
import com.studyagent.modules.tool.application.ReviewCardWriteTool;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 状态化学习 Agent 应用服务，按固定阶段推进学习会话并输出 SSE 事件。
 *
 * <p>当前 Agent 不走完全开放循环，而是围绕 PLAN、RETRIEVE、TEACH、QA、QUIZ、CARD、SUMMARY
 * 这些可观察阶段推进，方便做权限控制、工具审计和会话恢复。</p>
 */
@Service
@RequiredArgsConstructor
public class LearningAgentService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;
    private static final String STAGE_PLAN = "PLAN";
    private static final String STAGE_RETRIEVE = "RETRIEVE";
    private static final String STAGE_TEACH = "TEACH";
    private static final String STAGE_QA = "QA";
    private static final String STAGE_QUIZ = "QUIZ";
    private static final String STAGE_CARD = "CARD";
    private static final String STAGE_SUMMARY = "SUMMARY";
    private static final String STAGE_DONE = "DONE";

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentStepRecordMapper agentStepRecordMapper;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ReviewCardWriteTool reviewCardWriteTool;
    private final QuizService quizService;
    private final ContextMemoryService contextMemoryService;
    private final ChatGenerationService chatGenerationService;
    private final ObjectMapper objectMapper;

    /**
     * 创建学习会话和首个 Agent Run，并持久化用户初始学习目标。
     */
    @Transactional
    public LearningSessionResponse createSession(String message, List<Long> knowledgeBaseIds) {
        validateMessage(message);
        validateKnowledgeBases(knowledgeBaseIds);
        LocalDateTime now = LocalDateTime.now();

        ChatSession session = new ChatSession();
        session.setUserId(DEFAULT_USER_ID);
        session.setTitle(titleFrom(message));
        session.setMode("LEARNING_AGENT");
        session.setStatus("ACTIVE");
        session.setKnowledgeBaseScopeJson(toJson(knowledgeBaseIds));
        session.setWebSearchEnabled(false);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        chatSessionMapper.insert(session);

        insertMessage(session.getId(), DEFAULT_USER_ID, "USER", "TEXT", message, null, null, "{}");
        AgentRun run = createRun(session.getId(), DEFAULT_USER_ID, STAGE_PLAN);
        return new LearningSessionResponse(session.getId(), run.getId(), run.getStatus());
    }

    /**
     * 执行或继续一个学习会话，将阶段状态、工具状态和内容增量推送给 SSE sink。
     */
    public void runSession(Long sessionId, String message, Consumer<LearningAgentEvent> sink) {
        validateMessage(message);
        ChatSession session = requireSession(sessionId);
        List<Long> knowledgeBaseIds = readKnowledgeBaseIds(session.getKnowledgeBaseScopeJson());
        AgentRun run = requireRunningRun(session);
        insertUserMessageIfNeeded(sessionId, session.getUserId(), message);
        try {
            emit(sink, "session.started", Map.of("sessionId", sessionId, "agentRunId", run.getId()));
            // 恢复上下文时先加载最近压缩快照，再补充快照之后的原始消息。
            ContextMemoryService.RestoredContext restoredContext = contextMemoryService.restore(sessionId);
            String learningGoal = learningGoal(sessionId);
            AgentContext context = new AgentContext(session, run, learningGoal, message, knowledgeBaseIds, sink);

            // 根据当前 run 状态和用户消息决定本轮要执行的 Agent 阶段。
            String stage = routeStage(run, currentStage(run), message);
            emit(sink, "agent.stage.current", Map.of("stage", stage));
            switch (stage) {
                case STAGE_PLAN -> runPlanStage(context, restoredContext);
                case STAGE_RETRIEVE -> runRetrieveStage(context);
                case STAGE_TEACH -> runTeachStage(context);
                case STAGE_QA -> runQaStage(context);
                case STAGE_QUIZ -> runQuizStage(context);
                case STAGE_CARD -> runCardStage(context);
                case STAGE_SUMMARY -> runSummaryStage(context, restoredContext);
                case STAGE_DONE -> {
                    completeRun(run);
                    emit(sink, "done", Map.of("sessionId", sessionId, "agentRunId", run.getId(), "stage", STAGE_DONE));
                }
                default -> throw new BusinessException("未知 Agent 阶段: " + stage);
            }
        } catch (RuntimeException ex) {
            run.setErrorMessage(ex.getMessage());
            agentRunMapper.updateById(run);
            emit(sink, "error", Map.of("message", ex.getMessage()));
            throw ex;
        }
    }

    /**
     * PLAN 阶段：结合恢复上下文生成本轮学习计划，并推进到 RETRIEVE。
     */
    private void runPlanStage(AgentContext context, ContextMemoryService.RestoredContext restoredContext) {
        String output = executeStage(context, STAGE_PLAN, () -> generatePlan(context.learningGoal(), restoredContext));
        insertStageMessage(context, STAGE_PLAN, output);
        advanceStage(context.run(), STAGE_RETRIEVE);
        emitStageDone(context, STAGE_PLAN, STAGE_RETRIEVE);
    }

    /**
     * RETRIEVE 阶段：调用知识库检索工具并持久化检索结果。
     */
    private void runRetrieveStage(AgentContext context) {
        RagSearchResult searchResult = executeRetrieve(context);
        insertStageMessage(context, STAGE_RETRIEVE, toJson(searchResult));
        advanceStage(context.run(), STAGE_TEACH);
        emitStageDone(context, STAGE_RETRIEVE, STAGE_TEACH);
    }

    /**
     * TEACH 阶段：基于检索引用讲解知识点。
     */
    private void runTeachStage(AgentContext context) {
        String plan = stageText(context.run().getId(), STAGE_PLAN);
        RagSearchResult searchResult = stageSearchResult(context.run().getId());
        String output = executeStage(context, STAGE_TEACH,
                () -> generateTeaching(context.message(), plan, searchResult.references()));
        insertStageMessage(context, STAGE_TEACH, output);
        advanceStage(context.run(), STAGE_QA);
        emitStageDone(context, STAGE_TEACH, STAGE_QA);
    }

    /**
     * QA 阶段：围绕上一轮检索结果回答用户追问。
     */
    private void runQaStage(AgentContext context) {
        RagSearchResult searchResult = stageSearchResult(context.run().getId());
        String output = executeStage(context, STAGE_QA,
                () -> generateQa(context.message(), searchResult.references()));
        insertStageMessage(context, STAGE_QA, output);
        // 用户明确要求继续时才推进到测验，否则保持在 QA，便于多轮追问。
        String nextStage = asksToContinue(context.message()) ? STAGE_QUIZ : STAGE_QA;
        advanceStage(context.run(), nextStage);
        emitStageDone(context, STAGE_QA, nextStage);
    }

    /**
     * QUIZ 阶段：根据引用资料生成并落库即时测验题。
     */
    private void runQuizStage(AgentContext context) {
        RagSearchResult searchResult = stageSearchResult(context.run().getId());
        String output = executeStage(context, STAGE_QUIZ, () -> {
            List<QuizQuestionResponse> questions = quizService.createFromReferences(
                    context.session().getUserId(),
                    context.session().getId(),
                    context.run().getId(),
                    searchResult.references()
            );
            emit(context.sink(), "quiz.generated", Map.of(
                    "content", questions.isEmpty() ? "本轮没有可用引用资料，暂不生成测验。" : "已生成测验题：" + questions.size(),
                    "questions", questions
            ));
            if (questions.isEmpty()) {
                return "本轮没有可用引用资料，暂不生成测验。";
            }
            return toJson(questions);
        });
        insertStageMessage(context, STAGE_QUIZ, output);
        advanceStage(context.run(), STAGE_QA);
        emitStageDone(context, STAGE_QUIZ, STAGE_QA);
    }

    /**
     * CARD 阶段：生成复习卡文本，并通过工具写入复习卡表。
     */
    private void runCardStage(AgentContext context) {
        RagSearchResult searchResult = stageSearchResult(context.run().getId());
        String output = executeCardStage(context, searchResult.references());
        insertStageMessage(context, STAGE_CARD, output);
        advanceStage(context.run(), STAGE_SUMMARY);
        emitStageDone(context, STAGE_CARD, STAGE_SUMMARY);
    }

    /**
     * SUMMARY 阶段：压缩本轮上下文、写入快照并完成 Agent Run。
     */
    private void runSummaryStage(AgentContext context, ContextMemoryService.RestoredContext restoredContext) {
        String plan = stageText(context.run().getId(), STAGE_PLAN);
        String teaching = stageText(context.run().getId(), STAGE_TEACH);
        String qa = stageText(context.run().getId(), STAGE_QA);
        String quiz = stageText(context.run().getId(), STAGE_QUIZ);
        String cards = stageText(context.run().getId(), STAGE_CARD);
        String output = executeStage(context, STAGE_SUMMARY, () -> generateSummary(plan, teaching, qa, quiz, cards));
        ChatMessage assistantMessage = insertStageMessage(context, STAGE_SUMMARY, output);
        // 快照覆盖到 SUMMARY 消息，原始消息仍然保留在 chat_messages 中。
        MemorySnapshotResult snapshotResult = compressMemory(
                context.session().getId(),
                assistantMessage.getId(),
                output,
                context.sink()
        );
        completeRun(context.run());
        emit(context.sink(), "done", Map.of(
                "sessionId", context.session().getId(),
                "agentRunId", context.run().getId(),
                "stage", STAGE_SUMMARY,
                "snapshotId", snapshotResult.snapshotId(),
                "coveredMessageId", snapshotResult.coveredMessageId()
        ));
    }

    /**
     * 执行复习卡生成和写入工具调用。
     */
    private String executeCardStage(AgentContext context, List<RagReference> references) {
        String generatedCards = executeStage(context, "CARD", () -> generateCards(context.learningGoal(), references));
        if (references.isEmpty()) {
            return generatedCards;
        }
        List<ReviewCardWriteTool.CardDraft> drafts = buildCardDrafts(context, references);
        emit(context.sink(), "tool.started", Map.of("toolName", ReviewCardWriteTool.TOOL_NAME));
        try {
            List<ReviewCardResponse> cards = reviewCardWriteTool.writeCards(
                    context.run().getId(),
                    context.session().getId(),
                    context.session().getUserId(),
                    context.knowledgeBaseIds(),
                    drafts
            );
            emit(context.sink(), "tool.completed", Map.of(
                    "toolName", ReviewCardWriteTool.TOOL_NAME,
                    "cardCount", cards.size()
            ));
            return generatedCards + "\n\n已写入复习卡数量：" + cards.size();
        } catch (RuntimeException ex) {
            emit(context.sink(), "tool.failed", Map.of(
                    "toolName", ReviewCardWriteTool.TOOL_NAME,
                    "message", ex.getMessage()
            ));
            throw ex;
        }
    }

    /**
     * 从引用资料构造复习卡草稿，最多生成三张，避免一次写入过多低质量卡片。
     */
    private List<ReviewCardWriteTool.CardDraft> buildCardDrafts(AgentContext context, List<RagReference> references) {
        List<ReviewCardWriteTool.CardDraft> drafts = new ArrayList<>();
        int limit = Math.min(3, references.size());
        for (int i = 0; i < limit; i++) {
            RagReference reference = references.get(i);
            drafts.add(new ReviewCardWriteTool.CardDraft(
                    reference.knowledgeBaseId(),
                    reference.documentId(),
                    "请回忆：" + compact(reference.content(), 80),
                    compact(reference.content(), 420),
                    List.of("agent", "auto"),
                    null,
                    List.of(reference.chunkId())
            ));
        }
        return drafts;
    }

    /**
     * 压缩会话记忆并发送上下文压缩完成事件。
     */
    private MemorySnapshotResult compressMemory(
            Long sessionId,
            Long coveredMessageId,
            String summary,
            Consumer<LearningAgentEvent> sink
    ) {
        var snapshot = contextMemoryService.compressAfterRound(sessionId, coveredMessageId, summary);
        emit(sink, "context.summary.completed", Map.of(
                "snapshotId", snapshot.getId(),
                "coveredMessageId", snapshot.getCoveredMessageId(),
                "tokenCount", snapshot.getTokenCount()
        ));
        return new MemorySnapshotResult(snapshot.getId(), snapshot.getCoveredMessageId());
    }

    /**
     * 执行 RETRIEVE 阶段并完整记录工具开始、完成或失败事件。
     */
    private RagSearchResult executeRetrieve(AgentContext context) {
        AgentStepRecord step = startStep(context.run().getId(), "RETRIEVE", toJson(Map.of(
                "question", context.learningGoal(),
                "knowledgeBaseIds", context.knowledgeBaseIds()
        )));
        emit(context.sink(), "stage.started", Map.of("stage", "RETRIEVE"));
        emit(context.sink(), "tool.started", Map.of("toolName", KnowledgeSearchTool.TOOL_NAME));
        try {
            RagSearchResult result = knowledgeSearchTool.search(
                    context.run().getId(),
                    context.session().getId(),
                    context.session().getUserId(),
                    context.knowledgeBaseIds(),
                    context.learningGoal()
            );
            completeStep(step, toJson(result));
            emit(context.sink(), "tool.completed", Map.of(
                    "toolName", KnowledgeSearchTool.TOOL_NAME,
                    "hitCount", result.references().size()
            ));
            emit(context.sink(), "stage.completed", Map.of("stage", "RETRIEVE"));
            return result;
        } catch (RuntimeException ex) {
            failStep(step, ex.getMessage());
            emit(context.sink(), "tool.failed", Map.of(
                    "toolName", KnowledgeSearchTool.TOOL_NAME,
                    "message", ex.getMessage()
            ));
            throw ex;
        }
    }

    /**
     * 执行普通 Agent 阶段，统一记录 step、发送阶段事件并保存输出。
     */
    private String executeStage(AgentContext context, String stage, StageAction action) {
        AgentStepRecord step = startStep(context.run().getId(), stage, toJson(Map.of(
                "learningGoal", context.learningGoal(),
                "userMessage", context.message()
        )));
        updateRunStage(context.run(), stage);
        emit(context.sink(), "stage.started", Map.of("stage", stage));
        try {
            String output = action.run();
            completeStep(step, toJson(Map.of("text", output)));
            emitStageOutput(context.sink(), stage, output);
            emit(context.sink(), "stage.completed", Map.of("stage", stage));
            return output;
        } catch (RuntimeException ex) {
            failStep(step, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 生成学习计划提示。
     */
    private String generatePlan(String message, ContextMemoryService.RestoredContext restoredContext) {
        return chatGenerationService.generate(
                "你是学习 Agent 的 PLAN 节点。请为用户本轮学习生成 3-5 步学习计划，简洁、可执行。",
                "最近压缩记忆与未压缩增量上下文：\n" + contextText(restoredContext) + "\n\n学习目标或问题：" + message
        );
    }

    /**
     * 基于引用资料生成讲解内容。
     */
    private String generateTeaching(String message, String plan, List<RagReference> references) {
        if (references.isEmpty()) {
            return "知识库未检索到相关内容，暂时无法基于资料讲解。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 TEACH 节点。只依据引用资料讲解知识点，不编造来源。",
                "用户问题：" + message + "\n学习计划：\n" + plan + "\n\n引用资料：\n" + referencesText(references)
        );
    }

    /**
     * 基于引用资料回答用户追问。
     */
    private String generateQa(String message, List<RagReference> references) {
        if (references.isEmpty()) {
            return "知识库未检索到相关内容，无法做基于资料的答疑。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 QA 节点。请直接回答用户疑问，关键结论标注引用编号。",
                "问题：" + message + "\n引用资料：\n" + referencesText(references)
        );
    }

    /**
     * 生成即时测验文本，保留给纯文本测验场景复用。
     */
    private String generateQuiz(String message, List<RagReference> references) {
        if (references.isEmpty()) {
            return "本轮没有可用引用资料，暂不生成测验。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 QUIZ 节点。请生成 3 道即时测验题，每题包含答案和解析。",
                "学习主题：" + message + "\n引用资料：\n" + referencesText(references)
        );
    }

    /**
     * 生成复习卡展示文本，真正写卡由 review_card_write 工具完成。
     */
    private String generateCards(String message, List<RagReference> references) {
        if (references.isEmpty()) {
            return "本轮没有可用引用资料，暂不生成复习卡。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 CARD 节点。请生成 3 张复习卡，格式为 Front/Back/Tags。",
                "学习主题：" + message + "\n引用资料：\n" + referencesText(references)
        );
    }

    /**
     * 生成本轮上下文摘要，后续会作为长期记忆快照内容。
     */
    private String generateSummary(String plan, String teaching, String qa, String quiz, String cards) {
        return chatGenerationService.generate(
                "你是学习 Agent 的 SUMMARY 节点。请压缩本轮学习上下文，保留目标、关键结论、待复习点。",
                "计划：\n" + plan + "\n讲解：\n" + teaching + "\n答疑：\n" + qa + "\n测验：\n" + quiz + "\n复习卡：\n" + cards
        );
    }

    /**
     * 按阶段类型发送内容事件，前端可用事件名决定展示区域。
     */
    private void emitStageOutput(Consumer<LearningAgentEvent> sink, String stage, String output) {
        if ("QUIZ".equals(stage)) {
            emit(sink, "quiz.generated", Map.of("content", output));
        } else if ("CARD".equals(stage)) {
            emit(sink, "card.generated", Map.of("content", output));
        } else if ("SUMMARY".equals(stage)) {
            emit(sink, "token.delta", Map.of("stage", stage, "content", output));
        } else {
            emit(sink, "token.delta", Map.of("stage", stage, "content", output));
        }
    }

    /**
     * 将恢复出来的快照和增量消息拼成 PLAN 阶段可读上下文。
     */
    private String contextText(ContextMemoryService.RestoredContext restoredContext) {
        StringBuilder builder = new StringBuilder();
        if (restoredContext.snapshot() != null) {
            builder.append("压缩记忆覆盖到 messageId=")
                    .append(restoredContext.snapshot().getCoveredMessageId())
                    .append("：\n")
                    .append(restoredContext.snapshot().getSummaryContent())
                    .append("\n\n");
        }
        if (restoredContext.messages().isEmpty()) {
            return builder.append("未压缩增量消息：无").toString();
        }
        builder.append("未压缩增量消息：\n");
        for (ChatMessage message : restoredContext.messages()) {
            builder.append(message.getRole())
                    .append("#")
                    .append(message.getId())
                    .append(": ")
                    .append(compact(message.getContent(), 360))
                    .append("\n");
        }
        return builder.toString();
    }

    /**
     * 将引用资料格式化为带编号的 prompt 文本。
     */
    private String referencesText(List<RagReference> references) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            RagReference reference = references.get(i);
            builder.append("[引用").append(i + 1).append("]")
                    .append(" documentId=").append(reference.documentId())
                    .append(", chunkId=").append(reference.chunkId())
                    .append(", title=").append(reference.documentTitle())
                    .append("\n")
                    .append(reference.content())
                    .append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 查询学习会话，不存在时返回明确业务错误。
     */
    private ChatSession requireSession(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("学习会话不存在: " + sessionId);
        }
        return session;
    }

    /**
     * 读取当前会话未完成的 run，不存在时创建一个新 run。
     */
    private AgentRun requireRunningRun(ChatSession session) {
        AgentRun run = agentRunMapper.selectRunningBySession(session.getId(), session.getUserId());
        if (run != null) {
            return run;
        }
        return createRun(session.getId(), session.getUserId(), STAGE_PLAN);
    }

    /**
     * 创建 Agent Run，记录当前所处阶段。
     */
    private AgentRun createRun(Long sessionId, Long userId, String currentStage) {
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setCurrentStage(currentStage);
        run.setStartedAt(LocalDateTime.now());
        agentRunMapper.insert(run);
        return run;
    }

    /**
     * 标记 Agent Run 完成。
     */
    private void completeRun(AgentRun run) {
        run.setStatus("COMPLETED");
        run.setFinishedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
    }

    /**
     * 更新 run 的当前阶段，作为恢复和下一轮路由依据。
     */
    private void updateRunStage(AgentRun run, String stage) {
        run.setCurrentStage(stage);
        agentRunMapper.updateById(run);
    }

    private void advanceStage(AgentRun run, String nextStage) {
        updateRunStage(run, nextStage);
    }

    /**
     * 读取当前阶段；历史数据缺失时回补为 PLAN。
     */
    private String currentStage(AgentRun run) {
        if (run.getCurrentStage() == null || run.getCurrentStage().isBlank()) {
            run.setCurrentStage(STAGE_PLAN);
            agentRunMapper.updateById(run);
            return STAGE_PLAN;
        }
        return run.getCurrentStage();
    }

    /**
     * 根据用户消息和当前阶段决定本轮执行哪个固定节点。
     */
    private String routeStage(AgentRun run, String currentStage, String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.trim().toLowerCase();
        if (isQuestion(normalized)) {
            return STAGE_QA;
        }
        if (containsAny(normalized, "测验", "测试", "quiz", "题目", "出题")) {
            return STAGE_QUIZ;
        }
        if (containsAny(normalized, "总结", "收尾", "summary")) {
            return STAGE_SUMMARY;
        }
        if (containsAny(normalized, "生成卡片", "复习卡", "card")) {
            return STAGE_CARD;
        }
        if (containsAny(normalized, "下一步", "继续", "推进", "next")) {
            if (STAGE_QA.equals(currentStage)) {
                return hasCompletedStep(run.getId(), STAGE_QUIZ) ? STAGE_CARD : STAGE_QUIZ;
            }
            return currentStage;
        }
        if (STAGE_QA.equals(currentStage) || STAGE_TEACH.equals(currentStage)) {
            return STAGE_QA;
        }
        return currentStage;
    }

    /**
     * 判断用户是否希望推进到下一个学习阶段。
     */
    private boolean asksToContinue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return containsAny(normalized, "下一步", "继续", "推进", "next");
    }

    /**
     * 粗略识别追问消息，优先进入 QA 阶段。
     */
    private boolean isQuestion(String value) {
        return value.contains("?")
                || value.contains("？")
                || containsAny(value, "为什么", "怎么", "如何", "哪里", "没懂", "不懂", "解释一下");
    }

    private boolean containsAny(String value, String... patterns) {
        for (String pattern : patterns) {
            if (value.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 发送阶段等待事件和本轮 SSE done 事件。
     */
    private void emitStageDone(AgentContext context, String completedStage, String nextStage) {
        emit(context.sink(), "agent.stage.waiting", Map.of(
                "completedStage", completedStage,
                "nextStage", nextStage,
                "message", "发送下一条消息继续推进到 " + nextStage
        ));
        emit(context.sink(), "done", Map.of(
                "sessionId", context.session().getId(),
                "agentRunId", context.run().getId(),
                "completedStage", completedStage,
                "nextStage", nextStage
        ));
    }

    /**
     * 将阶段输出保存为 assistant 消息，metadata 中记录 run 和 stage。
     */
    private ChatMessage insertStageMessage(AgentContext context, String stage, String content) {
        return insertMessage(
                context.session().getId(),
                context.session().getUserId(),
                "ASSISTANT",
                stage + "_RESULT",
                content,
                null,
                null,
                toJson(Map.of("agentRunId", context.run().getId(), "stage", stage))
        );
    }

    /**
     * 防止同一条用户消息在重入或重试时重复写入。
     */
    private void insertUserMessageIfNeeded(Long sessionId, Long userId, String message) {
        List<ChatMessage> messages = chatMessageMapper.selectAfter(sessionId, 0L);
        if (!messages.isEmpty()) {
            ChatMessage latest = messages.getLast();
            if ("USER".equals(latest.getRole()) && message.equals(latest.getContent())) {
                return;
            }
        }
        insertMessage(sessionId, userId, "USER", "TEXT", message, null, null, "{}");
    }

    /**
     * 获取会话第一条用户消息作为学习目标。
     */
    private String learningGoal(Long sessionId) {
        List<ChatMessage> messages = chatMessageMapper.selectAfter(sessionId, 0L);
        for (ChatMessage message : messages) {
            if ("USER".equals(message.getRole())) {
                return message.getContent();
            }
        }
        throw new BusinessException("学习会话缺少初始目标");
    }

    /**
     * 从阶段记录中读取文本输出。
     */
    private String stageText(Long agentRunId, String stage) {
        AgentStepRecord step = requireCompletedStep(agentRunId, stage);
        try {
            JsonNode root = objectMapper.readTree(step.getOutputJson());
            return root.path("text").asText("");
        } catch (Exception ex) {
            throw new BusinessException("读取阶段输出失败: " + stage + ", " + ex.getMessage());
        }
    }

    /**
     * 从 RETRIEVE 阶段记录中恢复检索结果。
     */
    private RagSearchResult stageSearchResult(Long agentRunId) {
        AgentStepRecord step = requireCompletedStep(agentRunId, STAGE_RETRIEVE);
        try {
            return objectMapper.readValue(step.getOutputJson(), RagSearchResult.class);
        } catch (Exception ex) {
            throw new BusinessException("读取检索阶段输出失败: " + ex.getMessage());
        }
    }

    /**
     * 读取指定阶段最近一次成功记录。
     */
    private AgentStepRecord requireCompletedStep(Long agentRunId, String stage) {
        AgentStepRecord step = agentStepRecordMapper.selectLatestCompleted(agentRunId, stage);
        if (step == null) {
            throw new BusinessException("缺少已完成的 Agent 阶段: " + stage);
        }
        return step;
    }

    /**
     * 判断某阶段是否已经成功执行过。
     */
    private boolean hasCompletedStep(Long agentRunId, String stage) {
        return agentStepRecordMapper.selectLatestCompleted(agentRunId, stage) != null;
    }

    /**
     * 创建 RUNNING 阶段记录。
     */
    private AgentStepRecord startStep(Long agentRunId, String stage, String inputJson) {
        AgentStepRecord step = new AgentStepRecord();
        step.setAgentRunId(agentRunId);
        step.setStage(stage);
        step.setStatus("RUNNING");
        step.setInputJson(inputJson);
        step.setStartedAt(LocalDateTime.now());
        agentStepRecordMapper.insert(step);
        return step;
    }

    /**
     * 完成阶段记录并保存输出 JSON。
     */
    private void completeStep(AgentStepRecord step, String outputJson) {
        step.setStatus("COMPLETED");
        step.setOutputJson(outputJson);
        step.setFinishedAt(LocalDateTime.now());
        agentStepRecordMapper.updateById(step);
    }

    /**
     * 标记阶段失败并保存错误信息。
     */
    private void failStep(AgentStepRecord step, String errorMessage) {
        step.setStatus("FAILED");
        step.setErrorMessage(errorMessage);
        step.setFinishedAt(LocalDateTime.now());
        agentStepRecordMapper.updateById(step);
    }

    /**
     * 持久化一条会话消息。
     */
    private ChatMessage insertMessage(
            Long sessionId,
            Long userId,
            String role,
            String messageType,
            String content,
            String toolName,
            String toolCallId,
            String metadataJson
    ) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(role);
        message.setMessageType(messageType);
        message.setContent(content);
        message.setToolName(toolName);
        message.setToolCallId(toolCallId);
        message.setMetadataJson(metadataJson);
        message.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(message);
        return message;
    }

    /**
     * 校验用户消息非空。
     */
    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new BusinessException("学习消息不能为空");
        }
    }

    /**
     * 校验会话必须绑定明确知识库范围。
     */
    private void validateKnowledgeBases(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException("知识库范围不能为空");
        }
    }

    /**
     * 从首条消息生成会话标题。
     */
    private String titleFrom(String message) {
        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 40) {
            return normalized;
        }
        return normalized.substring(0, 40);
    }

    /**
     * 序列化对象为 JSON，失败时转成业务异常。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON 序列化失败: " + ex.getMessage());
        }
    }

    /**
     * 从会话 JSON 中读取允许检索的知识库 ID。
     */
    private List<Long> readKnowledgeBaseIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new BusinessException("会话知识库范围解析失败: " + ex.getMessage());
        }
    }

    /**
     * 发送一个结构化 Agent 事件。
     */
    private void emit(Consumer<LearningAgentEvent> sink, String event, Object data) {
        sink.accept(new LearningAgentEvent(event, data));
    }

    /**
     * 压缩文本长度，用于标题、复习卡草稿和上下文摘要展示。
     */
    private String compact(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 可被 executeStage 执行的阶段动作。
     */
    @FunctionalInterface
    private interface StageAction {
        String run();
    }

    /**
     * 单次 Agent 执行中的上下文参数聚合。
     */
    private record AgentContext(
            ChatSession session,
            AgentRun run,
            String learningGoal,
            String message,
            List<Long> knowledgeBaseIds,
            Consumer<LearningAgentEvent> sink
    ) {
    }

    /**
     * 上下文压缩结果，用于 SSE done 事件返回快照信息。
     */
    private record MemorySnapshotResult(
            Long snapshotId,
            Long coveredMessageId
    ) {
    }
}
