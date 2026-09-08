package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.LearningConversationProperties;
import com.studyagent.mapper.*;
import com.studyagent.model.*;
import com.studyagent.review.ReviewCardService;
import org.junit.jupiter.api.Test;

class LearningTurnPersistenceTest {
    private final LearningSessionMapper sessions = mock(LearningSessionMapper.class);
    private final LearningTurnMapper turns = mock(LearningTurnMapper.class);
    private final LearningContextMapper contexts = mock(LearningContextMapper.class);
    private final LearningPersistenceService learning = mock(LearningPersistenceService.class);
    private final LearningTurnPersistence persistence = new LearningTurnPersistence(sessions, turns, contexts, learning,
            mock(ReviewCardService.class), new LearningConversationProperties(8000,700,3000,5,60,"LOCAL"), new ObjectMapper(), mock(LearningContextInitializer.class));

    @Test
    void sameRequestReturnsOriginalCompletedTurnButDifferentInputIsRejected() {
        var session = new LearningSession();
        var turn = new LearningTurn(); turn.setStatus("SUCCEEDED"); turn.setInputHash(LearningTurnPersistence.hash("hello"));
        when(sessions.selectOne(any())).thenReturn(session); when(turns.selectOne(any())).thenReturn(turn);
        assertThat(persistence.claim(1L, 10L, "id", "hello", "trace").execute()).isFalse();
        assertThatThrownBy(() -> persistence.claim(1L, 10L, "id", "different", "trace")).hasMessageContaining("不同消息");
        verifyNoInteractions(contexts, learning);
    }

    @Test
    void unfinishedArtifactsPreventDifferentRequestFromAdvancingTheSession() {
        var session = new LearningSession(); session.setActiveTurnId(30L);
        when(sessions.selectOne(any())).thenReturn(session);
        assertThatThrownBy(() -> persistence.claim(1L, 10L, "other", "hello", "trace")).hasMessageContaining("恢复回合 30");
        verifyNoInteractions(contexts, learning);
    }

    @Test
    void lostLeaseCannotCommitArtifactsOrContext() {
        var session = new LearningSession(); session.setActiveTurnId(30L);
        var turn = new LearningTurn(); turn.setId(30L); turn.setUserId(1L); turn.setSessionId(10L); turn.setProcessingToken("stale");
        when(sessions.selectOne(any())).thenReturn(session);
        when(turns.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> persistence.commitModel(turn, null)).hasMessageContaining("租约已失效");
        verifyNoInteractions(contexts, learning);
    }
}
