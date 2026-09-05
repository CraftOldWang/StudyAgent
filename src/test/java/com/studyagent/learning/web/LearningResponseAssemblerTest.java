package com.studyagent.learning.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.learning.LearningFlowService;
import com.studyagent.learning.QuizFeedback;
import com.studyagent.learning.QuizQuestionDraft;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import com.studyagent.model.ReviewCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningResponseAssemblerTest {

    @Test
    void completedSessionRestoresLastQuizFeedbackAndCardsWithoutRegeneration() {
        LearningFlowService flow = mock(LearningFlowService.class);
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setKnowledgeBaseId(2L);
        session.setLearningGoal("Java");
        session.setStatus("COMPLETED");
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSequenceNo(1);
        point.setTopic("Generics");
        point.setSubtopicsJson("[\"bounds\"]");
        point.setEstimatedMinutes(20);
        point.setStatus("COMPLETED");
        Quiz quiz = new Quiz();
        quiz.setId(30L);
        quiz.setScore(80);
        ReviewCard card = new ReviewCard();
        card.setId(40L);
        card.setFront("front");
        card.setBack("back");

        when(flow.loadSession(1L, 10L)).thenReturn(session);
        when(flow.listPoints(10L)).thenReturn(List.of(point));
        when(flow.findQuiz(point)).thenReturn(quiz);
        when(flow.existingCards(20L)).thenReturn(List.of(card));
        when(flow.readQuestions(null)).thenReturn(List.of(
                new QuizQuestionDraft("q", List.of("A", "B", "C", "D"), "A", "e", "c")));
        when(flow.readFeedback(null)).thenReturn(List.of(new QuizFeedback(0, true, "A", "e")));
        LearningResponseAssembler assembler = new LearningResponseAssembler(flow, new ObjectMapper());

        LearningSessionResponse response = assembler.session(1L, 10L);

        assertThat(response.activeKnowledgePoint()).isNull();
        assertThat(response.plan()).hasSize(1);
        assertThat(response.currentQuiz().score()).isEqualTo(80);
        assertThat(response.currentQuiz().feedback()).hasSize(1);
        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().getFirst().sourceChunkId()).isNull();
    }
}
