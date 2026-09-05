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
        LearningFlowService.TracedAnswer result = flowService.answerQuestion(
                currentUserContext.userId(), sessionId, request.message());
        return ApiResponse.ok(new LearningTurnResponse(
                result.traceId(), result.answer(), assembler.session(currentUserContext.userId(), sessionId)));
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
