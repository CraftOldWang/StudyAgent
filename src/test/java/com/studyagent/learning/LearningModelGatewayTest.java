package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
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
    void requiresAgentToolIntentBeforeAcceptingQuizJson() {
        point.setStatus("EXPLAINING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            RuntimeContext runtimeContext = invocation.getArgument(1);
            runtimeContext.get(LearningTransitionIntent.class).request(KnowledgePointStatus.QUIZZING);
            return Mono.just(message(quizJson()));
        });
        LearningModelGateway gateway = new LearningModelGateway(harnessAgent, scopeFactory, new ObjectMapper());

        assertThat(gateway.generateQuiz(session, point)).hasSize(5);
    }

    @Test
    void rejectsSuccessfulTextWhenAgentSkippedTransitionTool() {
        point.setStatus("NEW");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class)))
                .thenReturn(Mono.just(message("explanation")));
        LearningModelGateway gateway = new LearningModelGateway(harnessAgent, scopeFactory, new ObjectMapper());

        assertThatThrownBy(() -> gateway.explain(session, point))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未通过服务端工具");
    }

    @Test
    void allowsQuestionTurnWithoutStateChange() {
        point.setStatus("QUIZZING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class)))
                .thenReturn(Mono.just(message("answer")));
        LearningModelGateway gateway = new LearningModelGateway(harnessAgent, scopeFactory, new ObjectMapper());

        assertThat(gateway.answerQuestion(session, point, "why")).isEqualTo("answer");
    }

    @Test
    void acceptsNullCardSourceWithoutFabricatingId() {
        point.setStatus("CARD_GENERATING");
        when(harnessAgent.call(anyString(), any(RuntimeContext.class))).thenAnswer(invocation -> {
            RuntimeContext runtimeContext = invocation.getArgument(1);
            runtimeContext.get(LearningTransitionIntent.class).request(KnowledgePointStatus.COMPLETED);
            return Mono.just(message("""
                    [{"front":"f1","back":"b1","sourceChunkId":null},
                     {"front":"f2","back":"b2","sourceChunkId":null},
                     {"front":"f3","back":"b3","sourceChunkId":null}]
                    """));
        });
        LearningModelGateway gateway = new LearningModelGateway(harnessAgent, scopeFactory, new ObjectMapper());

        assertThat(gateway.generateCards(session, point))
                .hasSize(3)
                .allSatisfy(card -> assertThat(card.sourceChunkId()).isNull());
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
