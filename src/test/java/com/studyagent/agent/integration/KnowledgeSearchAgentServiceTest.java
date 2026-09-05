package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import com.studyagent.rag.retrieval.RetrievalHit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class KnowledgeSearchAgentServiceTest {

    @Test
    void bindsServerScopeAndReturnsAgentAnswer() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg reply = Msg.builder()
                .name("knowledge-search-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("接口引用可以指向不同实现对象。")
                .build();
        when(agent.call(eq("Java 多态是什么？"), org.mockito.ArgumentMatchers.any(RuntimeContext.class)))
                .thenAnswer(invocation -> {
                    RuntimeContext context = invocation.getArgument(1);
                    context.put(
                            KnowledgeSearchExecution.class,
                            new KnowledgeSearchExecution(new KnowledgeSearchResponse(
                                    "Java 多态是什么？",
                                    null,
                                    List.of(new KnowledgeSearchResponse.Result(
                                            "chunk-1",
                                            "多态资料",
                                            new RetrievalHit.Provenance(
                                                    "document-1", "Java 基础", "{\"page\":1}"),
                                            0.9)))));
                    return Mono.just(reply);
                });
        KnowledgeSearchAgentService service = new KnowledgeSearchAgentService(agent);

        KnowledgeSearchAgentService.AgentSearchResponse response =
                service.answer(11L, 22L, "  Java 多态是什么？  ");

        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).call(eq("Java 多态是什么？"), contextCaptor.capture());
        RuntimeContext context = contextCaptor.getValue();
        assertThat(context.getUserId()).isEqualTo("11");
        assertThat(context.getSessionId()).isNotBlank();
        assertThat(context.get(KnowledgeSearchScope.class))
                .isEqualTo(new KnowledgeSearchScope(11L, 22L));
        assertThat(response.query()).isEqualTo("Java 多态是什么？");
        assertThat(response.answer()).contains("不同实现对象");
        assertThat(response.toolInvoked()).isTrue();
        assertThat(response.hits()).singleElement()
                .extracting(KnowledgeSearchResponse.Result::chunkId)
                .isEqualTo("chunk-1");
    }

    @Test
    void doesNotInventToolEvidenceWhenModelDoesNotCallTool() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg reply = Msg.builder()
                .name("knowledge-search-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("无法回答。")
                .build();
        when(agent.call(eq("未知问题"), org.mockito.ArgumentMatchers.any(RuntimeContext.class)))
                .thenReturn(Mono.just(reply));

        KnowledgeSearchAgentService.AgentSearchResponse response =
                new KnowledgeSearchAgentService(agent).answer(11L, 22L, "未知问题");

        assertThat(response.toolInvoked()).isFalse();
        assertThat(response.hits()).isEmpty();
    }
}
