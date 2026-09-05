package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.agent.integration.LearningContextCompactor;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import com.studyagent.model.ReviewCard;
import com.studyagent.review.ReviewCardService;
import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LearningFlowServiceTest {

    private LearningPlanService planService;
    private LearningPersistenceService persistence;
    private LearningModelGateway modelGateway;
    private LearningTraceService traceService;
    private LearningContextCompactor compactor;
    private AgentInvocationScopeFactory scopeFactory;
    private ReviewCardService reviewCardService;
    private ReviewCardMapper reviewCardMapper;
    private LearningFlowService service;

    @BeforeEach
    void setUp() {
        planService = mock(LearningPlanService.class);
        persistence = mock(LearningPersistenceService.class);
        modelGateway = mock(LearningModelGateway.class);
        traceService = mock(LearningTraceService.class);
        compactor = mock(LearningContextCompactor.class);
        scopeFactory = mock(AgentInvocationScopeFactory.class);
        reviewCardService = mock(ReviewCardService.class);
        reviewCardMapper = mock(ReviewCardMapper.class);
        service = new LearningFlowService(
                planService,
                persistence,
                modelGateway,
                traceService,
                compactor,
                scopeFactory,
                reviewCardService,
                reviewCardMapper,
                new ObjectMapper());
        when(traceService.start()).thenReturn("trace-1");
    }

    @Test
    void rejectsUnownedKnowledgeBaseBeforeCallingPlanModel() {
        doThrow(new BusinessException("知识库不存在"))
                .when(scopeFactory).validateKnowledgeBaseScope(1L, 2L);

        assertThatThrownBy(() -> service.createSession(1L, 2L, "Java"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("知识库不存在");

        verify(planService, never()).generatePlan(anyString());
        verify(traceService, never()).start();
    }

    @Test
    void questionDuringQuizzingKeepsStateUnchanged() {
        LearningSession session = session();
        KnowledgePoint point = point(KnowledgePointStatus.QUIZZING);
        when(persistence.requireSession(1L, 10L)).thenReturn(session);
        when(persistence.requireActivePoint(session)).thenReturn(point);
        when(modelGateway.answerQuestion(session, point, "why")).thenReturn("answer");

        LearningFlowService.TracedAnswer result = service.answerQuestion(1L, 10L, "why");

        assertThat(result.answer()).isEqualTo("answer");
        assertThat(point.getStatus()).isEqualTo("QUIZZING");
        verify(persistence, never()).saveExplanationAndAdvance(any(), any(), anyString());
    }

    @Test
    void scoresAllFiveQuestionsWithoutPassThresholdAndAdvances() throws Exception {
        LearningSession session = session();
        KnowledgePoint point = point(KnowledgePointStatus.QUIZZING);
        Quiz quiz = new Quiz();
        quiz.setId(30L);
        List<QuizQuestionDraft> questions = List.of(
                question("A"), question("B"), question("C"), question("D"), question("A"));
        quiz.setQuestionsJson(new ObjectMapper().writeValueAsString(questions));
        when(persistence.requireSession(1L, 10L)).thenReturn(session);
        when(persistence.requireActivePoint(session)).thenReturn(point);
        when(persistence.requireQuiz(point)).thenReturn(quiz);

        LearningFlowService.QuizScore result = service.submitQuiz(
                1L, 10L, List.of("A", "wrong", "C", "wrong", "A"));

        assertThat(result.score()).isEqualTo(60);
        assertThat(result.feedback()).extracting(QuizFeedback::correct)
                .containsExactly(true, false, true, false, true);
        verify(persistence).saveQuizResultAndAdvance(eq(session), eq(quiz), eq(point), anyString(), eq(60), anyString());
    }

    @Test
    void compactsAndSavesAgentStateBeforeCompletingPoint() {
        LearningSession session = session();
        KnowledgePoint point = point(KnowledgePointStatus.CARD_GENERATING);
        ReviewCard first = new ReviewCard();
        first.setId(1L);
        ReviewCard second = new ReviewCard();
        second.setId(2L);
        ReviewCard third = new ReviewCard();
        third.setId(3L);
        RuntimeContext runtimeContext = RuntimeContext.builder().userId("1").sessionId("as-10").build();
        when(persistence.requireSession(1L, 10L)).thenReturn(session);
        when(persistence.requireActivePoint(session)).thenReturn(point);
        when(reviewCardMapper.selectList(any())).thenReturn(List.of(first, second, third));
        when(scopeFactory.createRuntimeContext("as-10", 1L, 2L, 20L)).thenReturn(runtimeContext);

        LearningFlowService.GeneratedCards result = service.generateCardsAndComplete(1L, 10L);

        assertThat(result.cards()).hasSize(3);
        InOrder order = inOrder(compactor, persistence);
        order.verify(compactor).compact(runtimeContext);
        order.verify(persistence).completePoint(session, point);
        verify(modelGateway, never()).generateCards(any(), any());
    }

    @Test
    void modelFailureRecordsErrorWithoutAdvancing() {
        LearningSession session = session();
        KnowledgePoint point = point(KnowledgePointStatus.EXPLAINING);
        when(persistence.requireSession(1L, 10L)).thenReturn(session);
        when(persistence.requireActivePoint(session)).thenReturn(point);
        when(modelGateway.generateQuiz(session, point)).thenThrow(new BusinessException("provider down"));

        assertThatThrownBy(() -> service.generateQuiz(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("provider down");

        verify(persistence).recordFailure(session, point, "provider down");
        verify(persistence, never()).saveQuizAndAdvance(any(), any(), anyString());
        assertThat(point.getStatus()).isEqualTo("EXPLAINING");
    }

    private LearningSession session() {
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setKnowledgeBaseId(2L);
        session.setLearningGoal("Java");
        session.setAgentscopeSessionId("as-10");
        session.setActiveKnowledgePointId(20L);
        session.setStatus("ACTIVE");
        return session;
    }

    private KnowledgePoint point(KnowledgePointStatus status) {
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSessionId(10L);
        point.setUserId(1L);
        point.setSequenceNo(1);
        point.setTopic("Generics");
        point.setStatus(status.name());
        return point;
    }

    private QuizQuestionDraft question(String correctAnswer) {
        return new QuizQuestionDraft(
                "question", List.of("A", "B", "C", "D"), correctAnswer, "explanation", "chunk-1");
    }
}
