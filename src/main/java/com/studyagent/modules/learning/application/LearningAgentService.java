package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

@Service
@RequiredArgsConstructor
public class LearningAgentService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentStepRecordMapper agentStepRecordMapper;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final ReviewCardWriteTool reviewCardWriteTool;
    private final ContextMemoryService contextMemoryService;
    private final ChatGenerationService chatGenerationService;
    private final ObjectMapper objectMapper;

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
        AgentRun run = createRun(session.getId(), DEFAULT_USER_ID);
        return new LearningSessionResponse(session.getId(), run.getId(), run.getStatus());
    }

    public void runSession(Long sessionId, String message, Consumer<LearningAgentEvent> sink) {
        validateMessage(message);
        ChatSession session = requireSession(sessionId);
        List<Long> knowledgeBaseIds = readKnowledgeBaseIds(session.getKnowledgeBaseScopeJson());
        insertMessage(sessionId, session.getUserId(), "USER", "TEXT", message, null, null, "{}");
        AgentRun run = createRun(sessionId, session.getUserId());
        try {
            emit(sink, "session.started", Map.of("sessionId", sessionId, "agentRunId", run.getId()));
            ContextMemoryService.RestoredContext restoredContext = contextMemoryService.restore(sessionId);
            AgentContext context = new AgentContext(session, run, message, knowledgeBaseIds, sink);

            String plan = executeStage(context, "PLAN", () -> generatePlan(message, restoredContext));
            RagSearchResult searchResult = executeRetrieve(context);
            String teaching = executeStage(context, "TEACH", () -> generateTeaching(message, plan, searchResult.references()));
            String qa = executeStage(context, "QA", () -> generateQa(message, searchResult.references()));
            String quiz = executeStage(context, "QUIZ", () -> generateQuiz(message, searchResult.references()));
            String cards = executeCardStage(context, searchResult.references());
            String summary = executeStage(context, "SUMMARY", () -> generateSummary(plan, teaching, qa, quiz, cards));

            String finalAnswer = finalAnswer(teaching, qa, quiz, cards, summary);
            ChatMessage assistantMessage = insertMessage(sessionId, session.getUserId(), "ASSISTANT", "AGENT_RESULT", finalAnswer, null, null, "{}");
            MemorySnapshotResult snapshotResult = compressMemory(sessionId, assistantMessage.getId(), summary, sink);
            completeRun(run);
            emit(sink, "done", Map.of(
                    "sessionId", sessionId,
                    "agentRunId", run.getId(),
                    "snapshotId", snapshotResult.snapshotId(),
                    "coveredMessageId", snapshotResult.coveredMessageId()
            ));
        } catch (RuntimeException ex) {
            failRun(run, ex.getMessage());
            emit(sink, "error", Map.of("message", ex.getMessage()));
            throw ex;
        }
    }

    private String executeCardStage(AgentContext context, List<RagReference> references) {
        String generatedCards = executeStage(context, "CARD", () -> generateCards(context.message(), references));
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

    private RagSearchResult executeRetrieve(AgentContext context) {
        AgentStepRecord step = startStep(context.run().getId(), "RETRIEVE", toJson(Map.of(
                "question", context.message(),
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
                    context.message()
            );
            completeStep(step, toJson(Map.of("hitCount", result.references().size())));
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

    private String executeStage(AgentContext context, String stage, StageAction action) {
        AgentStepRecord step = startStep(context.run().getId(), stage, toJson(Map.of("message", context.message())));
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

    private String generatePlan(String message, ContextMemoryService.RestoredContext restoredContext) {
        return chatGenerationService.generate(
                "你是学习 Agent 的 PLAN 节点。请为用户本轮学习生成 3-5 步学习计划，简洁、可执行。",
                "最近压缩记忆与未压缩增量上下文：\n" + contextText(restoredContext) + "\n\n学习目标或问题：" + message
        );
    }

    private String generateTeaching(String message, String plan, List<RagReference> references) {
        if (references.isEmpty()) {
            return "知识库未检索到相关内容，暂时无法基于资料讲解。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 TEACH 节点。只依据引用资料讲解知识点，不编造来源。",
                "用户问题：" + message + "\n学习计划：\n" + plan + "\n\n引用资料：\n" + referencesText(references)
        );
    }

    private String generateQa(String message, List<RagReference> references) {
        if (references.isEmpty()) {
            return "知识库未检索到相关内容，无法做基于资料的答疑。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 QA 节点。请直接回答用户疑问，关键结论标注引用编号。",
                "问题：" + message + "\n引用资料：\n" + referencesText(references)
        );
    }

    private String generateQuiz(String message, List<RagReference> references) {
        if (references.isEmpty()) {
            return "本轮没有可用引用资料，暂不生成测验。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 QUIZ 节点。请生成 3 道即时测验题，每题包含答案和解析。",
                "学习主题：" + message + "\n引用资料：\n" + referencesText(references)
        );
    }

    private String generateCards(String message, List<RagReference> references) {
        if (references.isEmpty()) {
            return "本轮没有可用引用资料，暂不生成复习卡。";
        }
        return chatGenerationService.generate(
                "你是学习 Agent 的 CARD 节点。请生成 3 张复习卡，格式为 Front/Back/Tags。",
                "学习主题：" + message + "\n引用资料：\n" + referencesText(references)
        );
    }

    private String generateSummary(String plan, String teaching, String qa, String quiz, String cards) {
        return chatGenerationService.generate(
                "你是学习 Agent 的 SUMMARY 节点。请压缩本轮学习上下文，保留目标、关键结论、待复习点。",
                "计划：\n" + plan + "\n讲解：\n" + teaching + "\n答疑：\n" + qa + "\n测验：\n" + quiz + "\n复习卡：\n" + cards
        );
    }

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

    private String finalAnswer(String teaching, String qa, String quiz, String cards, String summary) {
        return "## 知识讲解\n" + teaching
                + "\n\n## 答疑\n" + qa
                + "\n\n## 即时测验\n" + quiz
                + "\n\n## 复习卡\n" + cards
                + "\n\n## 本轮总结\n" + summary;
    }

    private ChatSession requireSession(Long sessionId) {
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("学习会话不存在: " + sessionId);
        }
        return session;
    }

    private AgentRun createRun(Long sessionId, Long userId) {
        AgentRun run = new AgentRun();
        run.setSessionId(sessionId);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        agentRunMapper.insert(run);
        return run;
    }

    private void completeRun(AgentRun run) {
        run.setStatus("COMPLETED");
        run.setFinishedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
    }

    private void failRun(AgentRun run, String errorMessage) {
        run.setStatus("FAILED");
        run.setErrorMessage(errorMessage);
        run.setFinishedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
    }

    private void updateRunStage(AgentRun run, String stage) {
        run.setCurrentStage(stage);
        agentRunMapper.updateById(run);
    }

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

    private void completeStep(AgentStepRecord step, String outputJson) {
        step.setStatus("COMPLETED");
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

    private void emit(Consumer<LearningAgentEvent> sink, String event, Object data) {
        sink.accept(new LearningAgentEvent(event, data));
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

    @FunctionalInterface
    private interface StageAction {
        String run();
    }

    private record AgentContext(
            ChatSession session,
            AgentRun run,
            String message,
            List<Long> knowledgeBaseIds,
            Consumer<LearningAgentEvent> sink
    ) {
    }

    private record MemorySnapshotResult(
            Long snapshotId,
            Long coveredMessageId
    ) {
    }
}
