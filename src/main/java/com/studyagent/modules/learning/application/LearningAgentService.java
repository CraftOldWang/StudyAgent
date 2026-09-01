package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.learning.domain.AgentRun;
import com.studyagent.modules.learning.domain.AgentStepRecord;
import com.studyagent.modules.learning.domain.ChatContextSnapshot;
import com.studyagent.modules.learning.domain.ChatMessage;
import com.studyagent.modules.learning.domain.ChatSession;
import com.studyagent.modules.learning.domain.LearningTodo;
import com.studyagent.modules.learning.infrastructure.AgentRunMapper;
import com.studyagent.modules.learning.infrastructure.AgentStepRecordMapper;
import com.studyagent.modules.learning.infrastructure.ChatMessageMapper;
import com.studyagent.modules.learning.infrastructure.ChatSessionMapper;
import com.studyagent.modules.learning.infrastructure.LearningTodoMapper;
import com.studyagent.modules.learning.interfaces.LearningSessionResponse;
import com.studyagent.modules.tool.application.LearningAgentToolContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todo 驱动的双层 LLM 学习 Agent。
 *
 * <p>核心边界是“工具调用归 Planner、状态转换归后端、用户文本归 Writer”。Planner 非流式调用模型，
 * 可以使用学习工具并输出结构化 decision；后端解析校验后，再让 Response Writer 流式生成用户可见自然语言。
 * 前端只消费后端 SSE 事件和 Writer 的纯文本 token，不需要也不应该解析模型原始 JSON。</p>
 */
@Service
@RequiredArgsConstructor
public class LearningAgentService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;
    private static final String SESSION_MODE = "LEARNING_AGENT_TODO";
    private static final String STAGE_PLAN = "PLAN";
    private static final String STAGE_AGENT_PLANNER = "AGENT_PLANNER";
    private static final String STAGE_RESPONSE_WRITER = "RESPONSE_WRITER";
    private static final String STAGE_TEACH = "TEACH";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String RUNNING = "RUNNING";
    private static final String TODO_PENDING = "PENDING";
    private static final String TODO_LEARNING = "LEARNING";
    private static final String TODO_DONE = "DONE";

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentStepRecordMapper agentStepRecordMapper;
    private final LearningTodoMapper learningTodoMapper;
    private final ContextMemoryService contextMemoryService;
    private final ChatGenerationService chatGenerationService;
    private final LearningPlanJsonCodec jsonCodec;
    private final AgentPlannerService agentPlannerService;
    private final ResponseWriterService responseWriterService;
    private final ToolCallTraceCollector toolCallTraceCollector;
    private final ObjectMapper objectMapper;

    /**
     * 创建学习会话。这里不立即调用模型，避免普通 REST 请求阻塞；PLAN 会在 SSE 入口中执行。
     */
    @Transactional
    public LearningSessionResponse createSession(String message, List<Long> knowledgeBaseIds) {
        validateMessage(message);
        validateKnowledgeBases(knowledgeBaseIds);
        LocalDateTime now = LocalDateTime.now();

        ChatSession session = new ChatSession();
        session.setUserId(DEFAULT_USER_ID);
        session.setTitle(titleFrom(message));
        session.setMode(SESSION_MODE);
        session.setStatus(STATUS_ACTIVE);
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
     * 执行 Agent 一轮交互：必要时先规划 Todo，然后按 Planner -> Writer -> 后端状态机推进。
     */
    public void runSession(Long sessionId, String message, Consumer<LearningAgentEvent> sink) {
        validateMessage(message);
        ChatSession session = requireLearningSession(sessionId);
        List<Long> knowledgeBaseIds = readKnowledgeBaseIds(session.getKnowledgeBaseScopeJson());
        AgentRun run = requireRunningRun(session);
        insertUserMessageIfNeeded(sessionId, session.getUserId(), message);

        try {
            emit(sink, "session.started", Map.of(
                    "sessionId", sessionId,
                    "agentRunId", run.getId(),
                    "mode", SESSION_MODE
            ));
            ContextMemoryService.RestoredContext restoredContext = contextMemoryService.restore(sessionId);
            String learningGoal = learningGoal(sessionId);

            if (learningTodoMapper.countBySession(sessionId) == 0) {
                runPlanStage(session, run, learningGoal, restoredContext, sink);
            }

            LearningTodo currentTodo = ensureCurrentTodo(session, run, sink);
            if (currentTodo == null) {
                completeRunAndSession(session, run);
                emit(sink, "done", Map.of(
                        "sessionId", sessionId,
                        "agentRunId", run.getId(),
                        "message", "全部学习 Todo 已完成"
                ));
                return;
            }

            TopicLoopResult result = runTopicLoop(
                    session,
                    run,
                    currentTodo,
                    learningGoal,
                    message,
                    knowledgeBaseIds,
                    restoredContext,
                    sink
            );
            ChatMessage assistantMessage = insertAssistantReply(session, run, currentTodo, result.reply(), result.decision());
            applyDecision(session, run, currentTodo, assistantMessage, result.decision(), sink);
        } catch (RuntimeException ex) {
            run.setErrorMessage(ex.getMessage());
            agentRunMapper.updateById(run);
            emit(sink, "error", Map.of("message", ex.getMessage()));
            throw ex;
        }
    }

    /**
     * PLAN 阶段：让模型把学习目标拆成知识点 Todo，并落库。
     */
    private void runPlanStage(
            ChatSession session,
            AgentRun run,
            String learningGoal,
            ContextMemoryService.RestoredContext restoredContext,
            Consumer<LearningAgentEvent> sink
    ) {
        AgentStepRecord step = startStep(run.getId(), STAGE_PLAN, toJson(Map.of(
                "learningGoal", learningGoal,
                "context", contextText(restoredContext)
        )));
        updateRunStage(run, STAGE_PLAN);
        emit(sink, "stage.started", Map.of("stage", STAGE_PLAN));
        try {
            String rawOutput = chatGenerationService.generate(planSystemPrompt(), planUserPrompt(learningGoal, restoredContext));
            LearningPlanJsonCodec.TodoPlanResult plan = jsonCodec.parsePlan(rawOutput);
            List<LearningTodo> todos = insertTodos(session, plan.todos());
            completeStep(step, toJson(Map.of("todos", todoViews(todos), "rawOutput", rawOutput)));
            insertMessage(
                    session.getId(),
                    session.getUserId(),
                    "ASSISTANT",
                    "PLAN_RESULT",
                    toJson(Map.of("todos", todoViews(todos))),
                    null,
                    null,
                    toJson(Map.of("agentRunId", run.getId(), "stage", STAGE_PLAN))
            );
            emit(sink, "todo.planned", Map.of("todos", todoViews(todos)));
            emit(sink, "stage.completed", Map.of("stage", STAGE_PLAN, "todoCount", todos.size()));
        } catch (RuntimeException ex) {
            failStep(step, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 双层 LLM Topic Loop：Planner 先做控制面决策，Writer 再流式输出用户可见文本。
     */
    private TopicLoopResult runTopicLoop(
            ChatSession session,
            AgentRun run,
            LearningTodo currentTodo,
            String learningGoal,
            String userMessage,
            List<Long> knowledgeBaseIds,
            ContextMemoryService.RestoredContext restoredContext,
            Consumer<LearningAgentEvent> sink
    ) {
        String contextText = contextText(restoredContext);
        String todoListText = todoListText(learningTodoMapper.selectBySession(session.getId()));
        ToolCallTraceCollector.Trace toolTrace = toolCallTraceCollector.create(sink);
        AgentPlannerService.PlannerResult plannerResult = runPlannerStep(
                session,
                run,
                currentTodo,
                learningGoal,
                userMessage,
                contextText,
                todoListText,
                knowledgeBaseIds,
                toolTrace,
                sink
        );
        return runWriterStep(
                session,
                run,
                currentTodo,
                learningGoal,
                userMessage,
                contextText,
                plannerResult.decision(),
                toolTrace,
                sink
        );
    }

    /**
     * Planner step：非流式，可调用工具，输出结构化 decision。原始 JSON 只进入 step 记录，不走 SSE。
     */
    private AgentPlannerService.PlannerResult runPlannerStep(
            ChatSession session,
            AgentRun run,
            LearningTodo currentTodo,
            String learningGoal,
            String userMessage,
            String contextText,
            String todoListText,
            List<Long> knowledgeBaseIds,
            ToolCallTraceCollector.Trace toolTrace,
            Consumer<LearningAgentEvent> sink
    ) {
        AgentStepRecord step = startStep(run.getId(), STAGE_AGENT_PLANNER, toJson(Map.of(
                "learningGoal", learningGoal,
                "userMessage", userMessage,
                "currentTodo", todoView(currentTodo),
                "todos", todoViews(learningTodoMapper.selectBySession(session.getId())),
                "knowledgeBaseIds", knowledgeBaseIds
        )));
        updateRunStage(run, STAGE_AGENT_PLANNER);
        emit(sink, "stage.started", Map.of(
                "stage", STAGE_AGENT_PLANNER,
                "topicId", currentTodo.getId(),
                "topicTitle", currentTodo.getTitle()
        ));
        try {
            AgentPlannerService.PlannerResult result = agentPlannerService.decide(new AgentPlannerService.PlannerRequest(
                    session,
                    currentTodo,
                    learningGoal,
                    userMessage,
                    contextText,
                    todoListText,
                    knowledgeBaseIds,
                    toolContext(run, session, knowledgeBaseIds),
                    toolTrace
            ));
            updateRunStage(run, result.decision().phase());
            completeStep(step, toJson(Map.of(
                    "decision", result.decision(),
                    "rawOutput", result.rawOutput(),
                    "repairAttempted", result.repairAttempted(),
                    "repairedOutput", result.repairedOutput(),
                    "toolTraces", toolTrace.traces()
            )));
            emit(sink, "agent.decision", Map.of(
                    "topicId", currentTodo.getId(),
                    "decision", result.decision()
            ));
            emit(sink, "stage.completed", Map.of(
                    "stage", STAGE_AGENT_PLANNER,
                    "topicId", currentTodo.getId(),
                    "phase", result.decision().phase(),
                    "nextAction", result.decision().nextAction()
            ));
            return result;
        } catch (RuntimeException ex) {
            failStep(step, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Writer step：流式、无工具、只输出用户可见自然语言。只有完整成功后，调用方才会落库 assistant 消息。
     */
    private TopicLoopResult runWriterStep(
            ChatSession session,
            AgentRun run,
            LearningTodo currentTodo,
            String learningGoal,
            String userMessage,
            String contextText,
            AgentPlannerDecision decision,
            ToolCallTraceCollector.Trace toolTrace,
            Consumer<LearningAgentEvent> sink
    ) {
        AgentStepRecord step = startStep(run.getId(), STAGE_RESPONSE_WRITER, toJson(Map.of(
                "learningGoal", learningGoal,
                "userMessage", userMessage,
                "currentTodo", todoView(currentTodo),
                "decision", decision,
                "toolSummary", toolTrace.summaryText()
        )));
        emit(sink, "stage.started", Map.of(
                "stage", decision.phase(),
                "topicId", currentTodo.getId(),
                "topicTitle", currentTodo.getTitle()
        ));
        StringBuilder replyBuilder = new StringBuilder();
        try {
            responseWriterService.stream(new ResponseWriterService.WriterRequest(
                    session,
                    currentTodo,
                    learningGoal,
                    userMessage,
                    contextText,
                    decision,
                    toolTrace.summaryText(),
                    toolTrace.referenceSummaryText()
            ), token -> {
                replyBuilder.append(token);
                emit(sink, "token.delta", Map.of(
                        "stage", decision.phase(),
                        "topicId", currentTodo.getId(),
                        "content", token
                ));
            });
            String reply = replyBuilder.toString();
            if (reply.isBlank()) {
                throw new BusinessException("Response Writer 输出为空");
            }
            completeStep(step, toJson(Map.of(
                    "reply", reply,
                    "decision", decision
            )));
            emit(sink, "stage.completed", Map.of(
                    "stage", decision.phase(),
                    "topicId", currentTodo.getId(),
                    "nextAction", decision.nextAction()
            ));
            return new TopicLoopResult(reply.trim(), decision);
        } catch (RuntimeException ex) {
            failStep(step, ex.getMessage());
            throw ex;
        }
    }

    /**
     * 按 Planner 决策推进当前 Todo。模型只给建议，真实修改在这里统一校验后执行。
     */
    private void applyDecision(
            ChatSession session,
            AgentRun run,
            LearningTodo currentTodo,
            ChatMessage assistantMessage,
            AgentPlannerDecision decision,
            Consumer<LearningAgentEvent> sink
    ) {
        switch (decision.nextAction()) {
            case "WAIT_USER", "CONTINUE_TOPIC" -> {
                keepLearning(currentTodo);
                emitWaiting(session, run, currentTodo, decision, sink);
            }
            case "COMPLETE_TOPIC" -> {
                completeTopicWithSummary(session, currentTodo, assistantMessage, decision, sink);
                emitAfterTopicCompleted(session, run, currentTodo, false, sink);
            }
            case "MOVE_NEXT_TOPIC" -> {
                completeTopicWithSummary(session, currentTodo, assistantMessage, decision, sink);
                LearningTodo nextTodo = startNextPendingTodo(session, sink);
                if (nextTodo == null) {
                    completeRunAndSession(session, run);
                    emit(sink, "done", Map.of(
                            "sessionId", session.getId(),
                            "agentRunId", run.getId(),
                            "completedTopicId", currentTodo.getId(),
                            "message", "当前知识点完成，全部 Todo 已完成"
                    ));
                } else {
                    updateRunStage(run, STAGE_TEACH);
                    emitAfterTopicCompleted(session, run, nextTodo, true, sink);
                }
            }
            case "FINISH_SESSION" -> {
                completeTopicWithSummary(session, currentTodo, assistantMessage, decision, sink);
                LearningTodo nextTodo = learningTodoMapper.selectNextPending(session.getId());
                if (nextTodo != null) {
                    throw new BusinessException("仍存在未完成 Todo，不能结束会话: " + nextTodo.getTitle());
                }
                completeRunAndSession(session, run);
                emit(sink, "done", Map.of(
                        "sessionId", session.getId(),
                        "agentRunId", run.getId(),
                        "completedTopicId", currentTodo.getId(),
                        "message", "学习会话已完成"
                ));
            }
            default -> throw new BusinessException("未知 Agent 动作: " + decision.nextAction());
        }
    }

    /**
     * 确保当前有一个正在学习的 Todo；没有则启动下一个 pending Todo。
     */
    private LearningTodo ensureCurrentTodo(ChatSession session, AgentRun run, Consumer<LearningAgentEvent> sink) {
        LearningTodo current = learningTodoMapper.selectCurrent(session.getId());
        if (current != null) {
            return current;
        }
        LearningTodo next = startNextPendingTodo(session, sink);
        if (next != null) {
            updateRunStage(run, STAGE_TEACH);
        }
        return next;
    }

    /**
     * 把下一个 pending Todo 切换成 LEARNING。
     */
    private LearningTodo startNextPendingTodo(ChatSession session, Consumer<LearningAgentEvent> sink) {
        LearningTodo next = learningTodoMapper.selectNextPending(session.getId());
        if (next == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        next.setStatus(TODO_LEARNING);
        next.setStartedAt(now);
        next.setUpdatedAt(now);
        learningTodoMapper.updateById(next);
        emit(sink, "todo.started", Map.of("todo", todoView(next)));
        return next;
    }

    /**
     * 当前知识点继续学习时，只刷新 updatedAt，保留 LEARNING 状态。
     */
    private void keepLearning(LearningTodo currentTodo) {
        currentTodo.setStatus(TODO_LEARNING);
        currentTodo.setUpdatedAt(LocalDateTime.now());
        learningTodoMapper.updateById(currentTodo);
    }

    /**
     * 完成当前知识点并写入上下文快照。
     */
    private void completeTopicWithSummary(
            ChatSession session,
            LearningTodo currentTodo,
            ChatMessage assistantMessage,
            AgentPlannerDecision decision,
            Consumer<LearningAgentEvent> sink
    ) {
        ChatMessage summaryMessage = insertMessage(
                session.getId(),
                session.getUserId(),
                "ASSISTANT",
                "SUMMARY_RESULT",
                decision.summary(),
                null,
                null,
                toJson(Map.of(
                        "topicId", currentTodo.getId(),
                        "sourceMessageId", assistantMessage.getId(),
                        "phase", decision.phase()
                ))
        );
        // 先压缩记忆再标记 Todo 完成。若压缩失败，Todo 仍停留在 LEARNING，原始消息也不会丢失。
        ChatContextSnapshot snapshot = contextMemoryService.compressAfterRound(
                session.getId(),
                summaryMessage.getId(),
                decision.summary()
        );

        LocalDateTime now = LocalDateTime.now();
        currentTodo.setStatus(TODO_DONE);
        currentTodo.setRoundSummary(decision.summary());
        currentTodo.setCompletedAt(now);
        currentTodo.setUpdatedAt(now);
        learningTodoMapper.updateById(currentTodo);

        emit(sink, "context.summary.completed", Map.of(
                "topicId", currentTodo.getId(),
                "snapshotId", snapshot.getId(),
                "coveredMessageId", snapshot.getCoveredMessageId()
        ));
        emit(sink, "todo.completed", Map.of("todo", todoView(currentTodo)));
    }

    private void emitWaiting(
            ChatSession session,
            AgentRun run,
            LearningTodo currentTodo,
            AgentPlannerDecision decision,
            Consumer<LearningAgentEvent> sink
    ) {
        emit(sink, "agent.stage.waiting", Map.of(
                "topicId", currentTodo.getId(),
                "topicTitle", currentTodo.getTitle(),
                "phase", decision.phase(),
                "nextAction", decision.nextAction(),
                "message", waitingMessage(decision)
        ));
        emit(sink, "done", Map.of(
                "sessionId", session.getId(),
                "agentRunId", run.getId(),
                "topicId", currentTodo.getId(),
                "phase", decision.phase(),
                "nextAction", decision.nextAction()
        ));
    }

    private void emitAfterTopicCompleted(
            ChatSession session,
            AgentRun run,
            LearningTodo todoForNextStep,
            boolean movedToNext,
            Consumer<LearningAgentEvent> sink
    ) {
        emit(sink, "agent.stage.waiting", Map.of(
                "topicId", todoForNextStep.getId(),
                "topicTitle", todoForNextStep.getTitle(),
                "phase", STAGE_TEACH,
                "nextAction", movedToNext ? "WAIT_NEXT_TOPIC_INPUT" : "WAIT_USER",
                "message", movedToNext ? "已切到下一个知识点，发送消息继续学习。" : "当前知识点已完成，发送消息进入下一个知识点。"
        ));
        emit(sink, "done", Map.of(
                "sessionId", session.getId(),
                "agentRunId", run.getId(),
                "topicId", todoForNextStep.getId(),
                "movedToNext", movedToNext
        ));
    }

    private String waitingMessage(AgentPlannerDecision decision) {
        if ("WAIT_USER".equals(decision.nextAction())) {
            return "等待用户追问、作答或输入继续指令。";
        }
        return "当前知识点仍在进行中，发送下一条消息继续推进。";
    }

    /**
     * 将 PLAN 结果批量写入 Todo 表。
     */
    private List<LearningTodo> insertTodos(
            ChatSession session,
            List<LearningPlanJsonCodec.TodoPlanItem> items
    ) {
        LocalDateTime now = LocalDateTime.now();
        List<LearningTodo> todos = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            LearningPlanJsonCodec.TodoPlanItem item = items.get(i);
            LearningTodo todo = new LearningTodo();
            todo.setSessionId(session.getId());
            todo.setUserId(session.getUserId());
            todo.setTitle(item.title());
            todo.setDescription(item.description());
            todo.setStatus(TODO_PENDING);
            todo.setOrderIndex(i + 1);
            todo.setCreatedAt(now);
            todo.setUpdatedAt(now);
            learningTodoMapper.insert(todo);
            todos.add(todo);
        }
        return todos;
    }

    /**
     * 保存 Writer 完整成功后的用户可见回复。Planner decision 只作为 metadata，真实状态另行校验。
     */
    private ChatMessage insertAssistantReply(
            ChatSession session,
            AgentRun run,
            LearningTodo currentTodo,
            String reply,
            AgentPlannerDecision decision
    ) {
        return insertMessage(
                session.getId(),
                session.getUserId(),
                "ASSISTANT",
                "TOPIC_REPLY",
                reply,
                null,
                null,
                toJson(Map.of(
                        "agentRunId", run.getId(),
                        "topicId", currentTodo.getId(),
                        "phase", decision.phase(),
                        "decision", decision
                ))
        );
    }

    private AgentRun requireRunningRun(ChatSession session) {
        AgentRun run = agentRunMapper.selectRunningBySession(session.getId(), session.getUserId());
        if (run != null) {
            return run;
        }
        return createRun(session.getId(), session.getUserId(), STAGE_PLAN);
    }

    private AgentRun createRun(Long sessionId, Long userId, String currentStage) {
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus(RUNNING);
        run.setCurrentStage(currentStage);
        run.setStartedAt(LocalDateTime.now());
        agentRunMapper.insert(run);
        return run;
    }

    private void completeRunAndSession(ChatSession session, AgentRun run) {
        run.setStatus(STATUS_COMPLETED);
        run.setFinishedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);

        session.setStatus(STATUS_COMPLETED);
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionMapper.updateById(session);
    }

    private void updateRunStage(AgentRun run, String stage) {
        run.setCurrentStage(stage);
        agentRunMapper.updateById(run);
    }

    private AgentStepRecord startStep(Long agentRunId, String stage, String inputJson) {
        AgentStepRecord step = new AgentStepRecord();
        step.setAgentRunId(agentRunId);
        step.setStage(stage);
        step.setStatus(RUNNING);
        step.setInputJson(inputJson);
        step.setStartedAt(LocalDateTime.now());
        agentStepRecordMapper.insert(step);
        return step;
    }

    private void completeStep(AgentStepRecord step, String outputJson) {
        step.setStatus(STATUS_COMPLETED);
        step.setOutputJson(outputJson);
        step.setFinishedAt(LocalDateTime.now());
        agentStepRecordMapper.updateById(step);
    }

    private void failStep(AgentStepRecord step, String errorMessage) {
        step.setStatus("FAILED");
        step.setErrorMessage(errorMessage);
        step.setFinishedAt(LocalDateTime.now());
        agentStepRecordMapper.updateById(step);
    }

    private ChatSession requireLearningSession(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("学习会话不存在: " + sessionId);
        }
        if (!SESSION_MODE.equals(session.getMode())) {
            throw new BusinessException("该会话不是当前学习 Agent 会话: " + sessionId);
        }
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            throw new BusinessException("学习会话已完成: " + sessionId);
        }
        return session;
    }

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

    private String learningGoal(Long sessionId) {
        List<ChatMessage> messages = chatMessageMapper.selectAfter(sessionId, 0L);
        for (ChatMessage message : messages) {
            if ("USER".equals(message.getRole())) {
                return message.getContent();
            }
        }
        throw new BusinessException("学习会话缺少初始目标");
    }

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

    private String planSystemPrompt() {
        return """
                你是学习 Agent 的计划模块。
                你的任务是把用户的大学习目标拆成 3 到 8 个知识点 Todo。
                每个 Todo 应该能在一轮学习循环中完成：讲解、答疑、练习巩固和总结。
                只输出一个 JSON 对象，不要输出 Markdown、解释或代码块。
                JSON 格式必须是：
                {
                  "todos": [
                    {"title": "知识点标题", "description": "这个知识点要解决的问题"}
                  ]
                }
                """;
    }

    private String planUserPrompt(String learningGoal, ContextMemoryService.RestoredContext restoredContext) {
        return "学习目标：\n" + learningGoal + "\n\n可恢复上下文：\n" + contextText(restoredContext);
    }

    private String todoListText(List<LearningTodo> todos) {
        if (todos.isEmpty()) {
            return "暂无 Todo";
        }
        StringBuilder builder = new StringBuilder();
        for (LearningTodo todo : todos) {
            builder.append(todo.getOrderIndex())
                    .append(". [")
                    .append(todo.getStatus())
                    .append("] ")
                    .append(todo.getTitle())
                    .append("\n");
        }
        return builder.toString();
    }

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
                    .append("(")
                    .append(message.getMessageType())
                    .append("): ")
                    .append(compact(message.getContent(), 360))
                    .append("\n");
        }
        return builder.toString();
    }

    /**
     * 构造 Spring AI ToolContext。
     *
     * <p>模型调用工具时只能提交 query、卡片 front/back 等业务参数；真正的 userId、sessionId、
     * agentRunId 和知识库授权范围由这里注入，工具执行时再从 ToolContext 读取。</p>
     */
    private Map<String, Object> toolContext(AgentRun run, ChatSession session, List<Long> knowledgeBaseIds) {
        return new LearningAgentToolContext(
                run.getId(),
                session.getId(),
                session.getUserId(),
                knowledgeBaseIds
        ).toToolContextMap();
    }

    private Map<String, Object> todoView(LearningTodo todo) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", todo.getId());
        view.put("title", todo.getTitle());
        view.put("description", todo.getDescription());
        view.put("status", todo.getStatus());
        view.put("orderIndex", todo.getOrderIndex());
        view.put("roundSummary", todo.getRoundSummary());
        return view;
    }

    private List<Map<String, Object>> todoViews(List<LearningTodo> todos) {
        return todos.stream()
                .map(this::todoView)
                .toList();
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new BusinessException("学习消息不能为空");
        }
    }

    private void validateKnowledgeBases(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException("知识库范围不能为空");
        }
    }

    private String titleFrom(String message) {
        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 40) {
            return normalized;
        }
        return normalized.substring(0, 40);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON 序列化失败: " + ex.getMessage());
        }
    }

    private List<Long> readKnowledgeBaseIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new BusinessException("会话知识库范围解析失败: " + ex.getMessage());
        }
    }

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

    private void emit(Consumer<LearningAgentEvent> sink, String event, Object data) {
        sink.accept(new LearningAgentEvent(event, data));
    }

    /**
     * Topic loop 的内部结果：Writer 的完整回复 + 后端已校验的 Planner 决策。
     */
    private record TopicLoopResult(String reply, AgentPlannerDecision decision) {
    }
}
