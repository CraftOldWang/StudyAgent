package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.json.JsonPayloadReader;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class LearningModelGatewayTest {

    private HarnessAgent harnessAgent;
    private AgentInvocationScopeFactory scopeFactory;
    private RuntimeContext context;
    private LearningSession session;
    private KnowledgePoint point;

    @BeforeEach
    void setUp() {
        harnessAgent = mock(HarnessAgent.class);
        scopeFactory = mock(AgentInvocationScopeFactory.class);
        context = RuntimeContext.builder().userId("1").sessionId("as-1").build();
        when(scopeFactory.createRuntimeContext("as-1", 1L, 2L, 3L)).thenReturn(context);
        session = new LearningSession();
        session.setUserId(1L);
        session.setKnowledgeBaseId(2L);
        session.setAgentscopeSessionId("as-1");
        session.setLearningGoal("Java");
        point = new KnowledgePoint();
        point.setId(3L);
        point.setTopic("Generics");
        point.setSubtopicsJson("[]");
    }

    @Test
    void acceptsQuizJsonWrappedInAgentNarrationAfterRequiredTools() {
        point.setStatus("EXPLAINING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            assertThat(invocation.<String>getArgument(0))
                    .contains(
                            "服务端权威当前状态：EXPLAINING",
                            "即使历史上下文声称测验已完成");
            RuntimeContext runtimeContext = invocation.getArgument(1);
            recordSearch(runtimeContext, "c1");
            runtimeContext.get(LearningTransitionIntent.class).request(KnowledgePointStatus.QUIZZING);
            return Mono.just(message("已生成 5 道题：\n" + quizJson() + "\n请查收。"));
        });
        LearningModelGateway gateway = gateway();

        assertThat(gateway.generateQuiz(session, point)).hasSize(5);
    }

    @Test
    void rejectsSuccessfulTextWhenAgentSkippedTransitionTool() {
        point.setStatus("NEW");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class)))
                .thenAnswer(invocation -> {
                    recordSearch(invocation.getArgument(1), "c1");
                    return Mono.just(message("explanation"));
                });
        LearningModelGateway gateway = gateway();

        assertThatThrownBy(() -> gateway.explain(session, point))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未通过服务端工具");
    }

    @Test
    void rejectsExplanationWhenAgentSkippedKnowledgeSearch() {
        point.setStatus("NEW");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            RuntimeContext runtimeContext = invocation.getArgument(1);
            runtimeContext.get(LearningTransitionIntent.class).request(KnowledgePointStatus.EXPLAINING);
            return Mono.just(message("explanation"));
        });
        LearningModelGateway gateway = gateway();

        assertThatThrownBy(() -> gateway.explain(session, point))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未调用 knowledge_search");
    }

    @Test
    void allowsQuestionTurnWithoutStateChange() {
        point.setStatus("QUIZZING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class)))
                .thenReturn(Mono.just(message("answer")));
        LearningModelGateway gateway = gateway();

        assertThat(gateway.answerQuestion(session, point, "why")).isEqualTo("answer");
    }

    @Test
    void acceptsNullCardSourceWithoutFabricatingId() {
        point.setStatus("CARD_GENERATING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            RuntimeContext runtimeContext = invocation.getArgument(1);
            recordSearch(runtimeContext);
            runtimeContext.get(LearningTransitionIntent.class).request(KnowledgePointStatus.COMPLETED);
            return Mono.just(message("""
                    [{"front":"f1","back":"b1","sourceChunkId":null},
                     {"front":"f2","back":"b2","sourceChunkId":null},
                     {"front":"f3","back":"b3","sourceChunkId":null}]
                    """));
        });
        LearningModelGateway gateway = gateway();

        assertThat(gateway.generateCards(session, point))
                .hasSize(3)
                .allSatisfy(card -> assertThat(card.sourceChunkId()).isNull());
    }

    @Test
    void rejectsQuizSourceThatWasNotReturnedByKnowledgeSearch() {
        point.setStatus("EXPLAINING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            RuntimeContext runtimeContext = invocation.getArgument(1);
            recordSearch(runtimeContext, "different-chunk");
            runtimeContext.get(LearningTransitionIntent.class).request(KnowledgePointStatus.QUIZZING);
            return Mono.just(message(quizJson()));
        });
        LearningModelGateway gateway = gateway();

        assertThatThrownBy(() -> gateway.generateQuiz(session, point))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本次 knowledge_search 结果中");
    }

    private void recordSearch(RuntimeContext runtimeContext, String... chunkIds) {
        List<KnowledgeSearchResponse.Result> hits = java.util.Arrays.stream(chunkIds)
                .map(id -> new KnowledgeSearchResponse.Result(id, "content", null, 1.0))
                .toList();
        KnowledgeSearchExecution execution = runtimeContext.get(KnowledgeSearchExecution.class);
        assertThat(execution).isNotNull();
        execution.append(new KnowledgeSearchResponse("query", null, hits));
    }

    private LearningModelGateway gateway() {
        return new LearningModelGateway(
                harnessAgent,
                scopeFactory,
                new JsonPayloadReader(new ObjectMapper()));
    }

    private Msg message(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private String quizJson() {
        return """
                [{"question":"q1","options":["A","B","C","D"],"correctAnswer":"A","explanation":"e","sourceChunkId":"c1"},
                 {"question":"q2","options":["A","B","C","D"],"correctAnswer":"B","explanation":"e","sourceChunkId":"c1"},
                 {"question":"q3","options":["A","B","C","D"],"correctAnswer":"C","explanation":"e","sourceChunkId":"c1"},
                 {"question":"q4","options":["A","B","C","D"],"correctAnswer":"D","explanation":"e","sourceChunkId":"c1"},
                 {"question":"q5","options":["A","B","C","D"],"correctAnswer":"A","explanation":"e","sourceChunkId":"c1"}]
                """;
    }
}
