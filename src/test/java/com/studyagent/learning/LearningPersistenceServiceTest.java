package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.mapper.KnowledgePointMapper;
import com.studyagent.mapper.LearningPlanMapper;
import com.studyagent.mapper.LearningSessionMapper;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LearningPersistenceServiceTest {

    @Test
    void failureUpdateDoesNotRepersistMutatedBusinessState() {
        LearningSessionMapper sessionMapper = mock(LearningSessionMapper.class);
        KnowledgePointMapper pointMapper = mock(KnowledgePointMapper.class);
        LearningPersistenceService service = new LearningPersistenceService(
                sessionMapper,
                mock(LearningPlanMapper.class),
                pointMapper,
                mock(QuizMapper.class),
                new ObjectMapper());
        LearningSession session = new LearningSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setStatus("COMPLETED");
        session.setActiveKnowledgePointId(null);
        KnowledgePoint point = new KnowledgePoint();
        point.setId(20L);
        point.setSessionId(10L);
        point.setStatus("COMPLETED");

        service.recordFailure(session, point, "database failure");

        ArgumentCaptor<UpdateWrapper<LearningSession>> sessionUpdate = ArgumentCaptor.forClass(UpdateWrapper.class);
        ArgumentCaptor<UpdateWrapper<KnowledgePoint>> pointUpdate = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sessionMapper).update(isNull(), sessionUpdate.capture());
        verify(pointMapper).update(isNull(), pointUpdate.capture());
        assertThat(sessionUpdate.getValue().getSqlSet())
                .contains("error_message", "updated_at")
                .doesNotContain("status", "active_knowledge_point_id");
        assertThat(pointUpdate.getValue().getSqlSet())
                .contains("error_message", "updated_at")
                .doesNotContain("status", "explanation", "completed_at");
        verify(sessionMapper, never()).updateById(any(LearningSession.class));
        verify(pointMapper, never()).updateById(any(KnowledgePoint.class));
    }
}
