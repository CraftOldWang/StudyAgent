package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.studyagent.config.LearningPlanningProperties;
import com.studyagent.mapper.LearningPlanRunMapper;
import com.studyagent.mapper.LearningPlanStageMapper;
import com.studyagent.model.LearningPlanRun;
import com.studyagent.model.LearningPlanStage;
import org.junit.jupiter.api.Test;

class PlanningPersistenceTest {
    private final LearningPlanRunMapper runs = mock(LearningPlanRunMapper.class);
    private final LearningPlanStageMapper stages = mock(LearningPlanStageMapper.class);
    private final PlanningPersistence persistence = new PlanningPersistence(runs, stages,
            new LearningPlanningProperties(4800,28000,6000,600));

    @Test void lostLeaseCannotCommitOrStartAnotherAttempt() {
        when(runs.update(isNull(), any())).thenReturn(0);
        LearningPlanStage stage = new LearningPlanStage(); stage.setRunId(1L);
        assertThatThrownBy(() -> persistence.finish(stage, "old-token")).hasMessageContaining("执行权");
        verifyNoInteractions(stages);
    }

    @Test void retryCreatesNewAttemptAndLeavesFailedRawResultIntact() {
        when(runs.update(isNull(), any())).thenReturn(1);
        LearningPlanStage previous = new LearningPlanStage(); previous.setAttemptCount(1); previous.setRawOutput("invalid json");
        when(stages.selectOne(any())).thenReturn(previous);
        LearningPlanRun run = new LearningPlanRun(); run.setId(1L); run.setUserId(2L);
        var next = persistence.begin(run,"token","OUTLINE","hash","{}","trace");
        assertThat(next.getAttemptCount()).isEqualTo(2);
        assertThat(previous.getRawOutput()).isEqualTo("invalid json");
        verify(stages).insert(next);
        verify(stages, never()).updateById(any(LearningPlanStage.class));
    }
}
