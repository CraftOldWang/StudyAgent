package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.AgentTraceEventMapper;
import com.studyagent.model.AgentTraceEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LearningTraceServiceTest {

    @Test
    void recordsOrderedSanitizedProductEvent() {
        AgentTraceEventMapper mapper = mock(AgentTraceEventMapper.class);
        when(mapper.selectCount(any())).thenReturn(2L);
        LearningTraceService service = new LearningTraceService(mapper);

        service.record(1L, "trace", 10L, "QUIZ", "MODEL_CALL", "done", "SUCCEEDED");

        ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(3);
        assertThat(captor.getValue().getSummary()).isEqualTo("done");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    void queryIsUserScopedAndMissingTraceIsExplicit() {
        AgentTraceEventMapper mapper = mock(AgentTraceEventMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        LearningTraceService service = new LearningTraceService(mapper);

        assertThatThrownBy(() -> service.find(1L, "missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("trace 不存在");
    }
}
