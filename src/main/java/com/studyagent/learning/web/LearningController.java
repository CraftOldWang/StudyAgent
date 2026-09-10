package com.studyagent.learning.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.learning.LearningFlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning/sessions")
@RequiredArgsConstructor
public class LearningController {

    private final LearningFlowService flowService;
    private final LearningResponseAssembler assembler;
    private final CurrentUserContext currentUserContext;
    private final com.studyagent.learning.LearningConversationService conversation;
    private final com.studyagent.learning.LearningTurnPersistence turns;
    private final com.studyagent.learning.LearningCardStageService cardStage;
    private final com.studyagent.learning.LearningToolDisplay toolDisplay;
    private final com.studyagent.learning.LearningCatalog catalog;

    @GetMapping
    public ApiResponse<java.util.List<com.studyagent.learning.LearningCatalog.SessionEntry>> list(
            @org.springframework.web.bind.annotation.RequestParam Long knowledgeBaseId) {
        return ApiResponse.ok(catalog.sessions(currentUserContext.userId(), knowledgeBaseId));
    }

    @GetMapping("/{sessionId}/turns/{turnId}/tools")
    public ApiResponse<?> tools(@PathVariable Long sessionId, @PathVariable Long turnId) {
        var turn = turns.require(currentUserContext.userId(), sessionId, turnId);
        return ApiResponse.ok(toolDisplay.history(currentUserContext.userId(), turn.getTraceId()));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{sessionId}/points/{pointId}/cards")
    public ApiResponse<LearningSessionResponse> editCards(@PathVariable Long sessionId, @PathVariable Long pointId,
            @RequestBody java.util.List<com.studyagent.learning.LearningCardStageService.Edit> edits) {
        cardStage.edit(currentUserContext.userId(), sessionId, pointId, edits);
        return ApiResponse.ok(assembler.session(currentUserContext.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/points/{pointId}/cards/confirm")
    public ApiResponse<LearningSessionResponse> confirmCards(@PathVariable Long sessionId, @PathVariable Long pointId) {
        cardStage.confirm(currentUserContext.userId(), sessionId, pointId);
        return ApiResponse.ok(assembler.session(currentUserContext.userId(), sessionId));
    }

    @PostMapping
    public ApiResponse<LearningMutationResponse.Created> create(
            @Valid @RequestBody CreateLearningSessionRequest request) {
        LearningFlowService.CreatedSession result = flowService.createSession(
                currentUserContext.userId(), request.knowledgeBaseId(), request.learningGoal());
        return ApiResponse.ok(new LearningMutationResponse.Created(
                result.traceId(), assembler.session(currentUserContext.userId(), result.session().getId())));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<LearningSessionResponse> get(@PathVariable Long sessionId) {
        return ApiResponse.ok(assembler.session(currentUserContext.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/explain")
    public ApiResponse<LearningTurnResponse> explain(@PathVariable Long sessionId) {
        LearningFlowService.TracedAnswer result = flowService.explain(currentUserContext.userId(), sessionId);
        return ApiResponse.ok(new LearningTurnResponse(
                result.traceId(), result.answer(), assembler.session(currentUserContext.userId(), sessionId)));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<LearningTurnResponse> message(
            @PathVariable Long sessionId,
            @Valid @RequestBody LearningMessageRequest request) {
        var result = conversation.message(currentUserContext.userId(), sessionId,
                request.requestId() == null ? java.util.UUID.randomUUID().toString() : request.requestId(), request.message(), event -> { });
        return ApiResponse.ok(new LearningTurnResponse(
                result.getTraceId(), result.getAssistantMessage(), assembler.session(currentUserContext.userId(), sessionId), result));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<java.util.List<com.studyagent.model.LearningTurn>> history(@PathVariable Long sessionId) {
        return ApiResponse.ok(turns.list(currentUserContext.userId(), sessionId));
    }

    @GetMapping("/{sessionId}/turns/{turnId}")
    public ApiResponse<com.studyagent.model.LearningTurn> turn(@PathVariable Long sessionId, @PathVariable Long turnId) {
        return ApiResponse.ok(turns.require(currentUserContext.userId(), sessionId, turnId));
    }

    @PostMapping("/{sessionId}/quiz")
    public ApiResponse<LearningMutationResponse.QuizGenerated> quiz(@PathVariable Long sessionId) {
        LearningFlowService.GeneratedQuiz result = flowService.generateQuiz(currentUserContext.userId(), sessionId);
        LearningSessionResponse session = assembler.session(currentUserContext.userId(), sessionId);
        return ApiResponse.ok(new LearningMutationResponse.QuizGenerated(
                result.traceId(), session.currentQuiz(), session));
    }

    @PostMapping("/{sessionId}/quiz/submit")
    public ApiResponse<LearningMutationResponse.QuizResult> submit(
            @PathVariable Long sessionId,
            @Valid @RequestBody QuizSubmissionRequest request) {
        LearningFlowService.QuizScore result = flowService.submitQuiz(
                currentUserContext.userId(), sessionId, request.answers());
        LearningSessionResponse session = assembler.session(currentUserContext.userId(), sessionId);
        return ApiResponse.ok(new LearningMutationResponse.QuizResult(
                result.traceId(),
                result.quizId(),
                result.score(),
                result.feedback().stream().map(assembler::feedback).toList(),
                session));
    }

    @PostMapping("/{sessionId}/cards")
    public ApiResponse<LearningMutationResponse.CardsGenerated> cards(@PathVariable Long sessionId) {
        LearningFlowService.GeneratedCards result = flowService.generateCardsAndComplete(
                currentUserContext.userId(), sessionId);
        LearningSessionResponse session = assembler.session(currentUserContext.userId(), sessionId);
        return ApiResponse.ok(new LearningMutationResponse.CardsGenerated(
                result.traceId(),
                result.knowledgePointId(),
                result.cards().stream().map(assembler::card).toList(),
                session));
    }
}
