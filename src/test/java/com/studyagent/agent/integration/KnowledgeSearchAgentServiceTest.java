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
                    KnowledgeSearchExecution execution = context.get(KnowledgeSearchExecution.class);
                    execution.append(new KnowledgeSearchResponse(
                            "Java 多态是什么？",
                            null,
                            List.of(new KnowledgeSearchResponse.Result(
                                    "chunk-1",
                                    "多态资料",
                                    new RetrievalHit.Provenance(
                                            "document-1", "Java 基础", "{\"page\":1}"),
                                    0.9))));
                    execution.append(new KnowledgeSearchResponse(
                            "动态派发",
                            null,
                            List.of(
                                    new KnowledgeSearchResponse.Result(
                                            "chunk-1",
                                            "重复资料",
                                            new RetrievalHit.Provenance(
                                                    "document-1", "Java 基础", "{\"page\":1}"),
                                            0.8),
                                    new KnowledgeSearchResponse.Result(
                                            "chunk-2",
                                            "动态派发资料",
                                            new RetrievalHit.Provenance(
                                                    "document-1", "Java 基础", "{\"page\":2}"),
                                            0.7))));
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
        assertThat(context.get(KnowledgeSearchExecution.class).retrievedChunkIds())
                .containsExactlyInAnyOrder("chunk-1", "chunk-2");
        assertThat(response.query()).isEqualTo("Java 多态是什么？");
        assertThat(response.answer()).contains("不同实现对象");
        assertThat(response.toolInvoked()).isTrue();
        assertThat(response.hits())
                .extracting(KnowledgeSearchResponse.Result::chunkId)
                .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void doesNotInventToolEvidenceWhenModelDoesNotCallTool() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg reply = Msg.builder()
                .name("knowledge-search-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("模型未经检索自行生成的内容。")
                .build();
        when(agent.call(eq("未知问题"), org.mockito.ArgumentMatchers.any(RuntimeContext.class)))
                .thenReturn(Mono.just(reply));

        KnowledgeSearchAgentService.AgentSearchResponse response =
                new KnowledgeSearchAgentService(agent).answer(11L, 22L, "未知问题");

        assertThat(response.toolInvoked()).isFalse();
        assertThat(response.hits()).isEmpty();
        assertThat(response.answer()).isEqualTo(KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE);
    }

    @Test
    void ignoresModelTextWhenActualToolExecutionHasNoEvidence() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg reply = Msg.builder()
                .name("knowledge-search-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("模型在空检索后仍编造的内容。")
                .build();
        when(agent.call(eq("空知识库问题"), org.mockito.ArgumentMatchers.any(RuntimeContext.class)))
                .thenAnswer(invocation -> {
                    RuntimeContext context = invocation.getArgument(1);
                    context.get(KnowledgeSearchExecution.class).append(new KnowledgeSearchResponse(
                            "空知识库问题",
                            KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE,
                            List.of()));
                    return Mono.just(reply);
                });

        KnowledgeSearchAgentService.AgentSearchResponse response =
                new KnowledgeSearchAgentService(agent).answer(11L, 22L, "空知识库问题");

        assertThat(response.toolInvoked()).isTrue();
        assertThat(response.hits()).isEmpty();
        assertThat(response.answer()).isEqualTo(KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE);
    }
}
