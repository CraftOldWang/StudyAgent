package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentInvocationScopeFactoryTest {

    @Mock
    private DocumentMapper documentMapper;

    @Test
    void validatesDocumentScopeAndStoresTypedScopeInRuntimeContext() {
        when(documentMapper.selectCount(any())).thenReturn(1L);
        AgentInvocationScopeFactory factory = new AgentInvocationScopeFactory(documentMapper);

        RuntimeContext context = factory.createRuntimeContext("session-1", 11L, 22L, 33L);

        assertThat(context.getUserId()).isEqualTo("11");
        assertThat(context.getSessionId()).isEqualTo("session-1");
        assertThat(context.get(AgentInvocationScope.class))
                .isEqualTo(new AgentInvocationScope(11L, 22L, 33L));
        verify(documentMapper).selectCount(any());
    }

    @Test
    void rejectsKnowledgeBaseWithoutDocumentForTheUser() {
        when(documentMapper.selectCount(any())).thenReturn(0L);
        AgentInvocationScopeFactory factory = new AgentInvocationScopeFactory(documentMapper);

        assertThatThrownBy(() -> factory.create(11L, 22L, 33L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库文档");
    }
}
