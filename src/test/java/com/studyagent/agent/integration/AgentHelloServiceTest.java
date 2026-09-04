package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class AgentHelloServiceTest {

    @Test
    void callsHarnessWithFixedPromptAndRequestRuntimeContext() {
        HarnessAgent harnessAgent = mock(HarnessAgent.class);
        Msg modelResponse = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("hello from model")
                .build();
        when(harnessAgent.call(eq("hello"), any(RuntimeContext.class)))
                .thenReturn(Mono.just(modelResponse));
        AgentHelloService service = new AgentHelloService(harnessAgent);

        String firstResponse = service.hello(42L);
        String secondResponse = service.hello(42L);

        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent, times(2)).call(eq("hello"), contextCaptor.capture());
        RuntimeContext firstContext = contextCaptor.getAllValues().get(0);
        RuntimeContext secondContext = contextCaptor.getAllValues().get(1);
        assertThat(firstContext.getUserId()).isEqualTo("42");
        assertThat(secondContext.getUserId()).isEqualTo("42");
        assertThat(firstContext.getSessionId()).isNotBlank();
        assertThat(secondContext.getSessionId()).isNotEqualTo(firstContext.getSessionId());
        assertThatCode(() -> UUID.fromString(firstContext.getSessionId())).doesNotThrowAnyException();
        assertThatCode(() -> UUID.fromString(secondContext.getSessionId())).doesNotThrowAnyException();
        assertThat(firstResponse).isEqualTo("hello from model");
        assertThat(secondResponse).isEqualTo("hello from model");
    }
}
