package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.model.LearningContext;
import com.studyagent.model.LearningSession;
import com.studyagent.model.LearningTurn;
import org.junit.jupiter.api.Test;

class LearningConversationServiceTest {
    private final LearningTurnPersistence turns = mock(LearningTurnPersistence.class);
    private final LearningPersistenceService learning = mock(LearningPersistenceService.class);
    private final LearningConversationGateway gateway = mock(LearningConversationGateway.class);
    private final LearningConversationCompactor compactor = mock(LearningConversationCompactor.class);
    private final LearningTraceService traces = mock(LearningTraceService.class);
    private final LearningConversationService service = new LearningConversationService(turns, learning, gateway, compactor, traces, new ObjectMapper());

    @Test
    void duplicateCompletedOrRunningRequestNeverCallsModel() {
        var turn = turn();
        when(traces.start()).thenReturn("t");
        when(turns.claim(1L, 10L, "request", "message", "t")).thenReturn(new LearningTurnPersistence.Claim(turn, false));
        assertThat(service.message(1L, 10L, "request", "message", e -> { })).isSameAs(turn);
        verifyNoInteractions(gateway, compactor, learning);
    }

    @Test
    void recoveryAfterArtifactsSkipsModelAndCompletesOnlyAfterPersistableSummary() {
        var turn = turn();
        var session = new LearningSession();
        var context = new LearningContext();
        when(traces.start()).thenReturn("t");
        when(turns.claim(1L, 10L, "request", "message", "t")).thenReturn(new LearningTurnPersistence.Claim(turn, true));
        when(learning.requireSession(1L, 10L)).thenReturn(session);
        when(turns.context(1L, 10L)).thenReturn(context);
        when(compactor.compact(session, turn, context)).thenReturn("compressed");
        when(turns.complete(turn, "compressed")).thenReturn(turn);
        service.message(1L, 10L, "request", "message", e -> { });
        verifyNoInteractions(gateway);
        var order = inOrder(compactor, turns);
        order.verify(compactor).compact(session, turn, context);
        order.verify(turns).complete(turn, "compressed");
    }

    @Test
    void summaryFailureRemainsFailedAndDoesNotMarkPointComplete() {
        var turn = turn();
        var session = new LearningSession();
        var context = new LearningContext();
        when(traces.start()).thenReturn("t");
        when(turns.claim(1L, 10L, "request", "message", "t")).thenReturn(new LearningTurnPersistence.Claim(turn, true));
        when(learning.requireSession(1L, 10L)).thenReturn(session);
        when(turns.context(1L, 10L)).thenReturn(context);
        when(compactor.compact(session, turn, context)).thenThrow(new IllegalStateException("summary unavailable"));
        when(turns.require(1L, 10L, 20L)).thenReturn(turn);
        service.message(1L, 10L, "request", "message", e -> { });
        verify(turns).fail(turn, "summary unavailable");
        verify(turns, never()).complete(any(), any());
        verifyNoInteractions(gateway);
    }

    private LearningTurn turn() {
        var turn = new LearningTurn();
        turn.setId(20L); turn.setUserId(1L); turn.setSessionId(10L); turn.setTraceId("t");
        turn.setPhase("ARTIFACTS_COMMITTED"); turn.setAttemptCount(2);
        return turn;
    }
}
