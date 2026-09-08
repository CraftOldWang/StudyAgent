package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.LearningTurn;
import org.junit.jupiter.api.Test;

class LearningFlowServiceTest {
    @Test
    void rejectsUnownedKnowledgeBaseBeforeCallingPlanModel() {
        var plan = mock(LearningPlanService.class);
        var scope = mock(AgentInvocationScopeFactory.class);
        doThrow(new BusinessException("not owned")).when(scope).validateKnowledgeBaseScope(1L, 2L);
        var service = new LearningFlowService(plan, mock(LearningPersistenceService.class),
                mock(LearningConversationService.class), mock(LearningTraceService.class), scope,
                mock(ReviewCardMapper.class), new ObjectMapper());
        assertThatThrownBy(() -> service.createSession(1L, 2L, "goal")).hasMessage("not owned");
        verifyNoInteractions(plan);
    }

    @Test
    void shortcutCannotBypassDurableConversationOrClaimAnArtifactForOrdinaryAnswer() {
        var conversation = mock(LearningConversationService.class);
        var learning = mock(LearningPersistenceService.class);
        var service = new LearningFlowService(mock(LearningPlanService.class), learning, conversation,
                mock(LearningTraceService.class), mock(AgentInvocationScopeFactory.class), mock(ReviewCardMapper.class), new ObjectMapper());
        var turn = new LearningTurn();
        turn.setStatus("SUCCEEDED"); turn.setAssistantMessage("need more information");
        turn.setArtifactJson("{\"type\":\"QUESTION\"}");
        when(conversation.message(eq(1L), eq(10L), anyString(), anyString(), any())).thenReturn(turn);
        assertThatThrownBy(() -> service.generateQuiz(1L, 10L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(learning);
    }
}
