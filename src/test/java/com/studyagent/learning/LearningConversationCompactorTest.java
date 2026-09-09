package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.LearningConversationProperties;
import com.studyagent.model.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.AgentState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class LearningConversationCompactorTest {
    private final LearningTurnPersistence turns = mock(LearningTurnPersistence.class);
    private final LearningCompactionPersistence summaries = mock(LearningCompactionPersistence.class);
    private final LearningTraceService traces = mock(LearningTraceService.class);

    @Test
    void pendingCardStageKeepsOriginalContextAndSummaryExcludesEarlierPoints() {
        var calls = new AtomicInteger();
        Model model = model(messages -> {
            calls.incrementAndGet();
            assertThat(messages.getLast().getTextContent()).contains("current fact").doesNotContain("earlier fact", "card rewrite");
            return answer("current summary");
        });
        var beforeCards = List.of(LearningContextMessages.summary("earlier fact", 1L, "POINT"), message("current fact", 2L, 30L));
        var input = LearningContextMessages.stateJson("1", "s", beforeCards);
        var duringCards = new java.util.ArrayList<>(beforeCards);
        duringCards.add(message("card rewrite ".repeat(6000), 2L, 31L));
        var turn = turn(duringCards);
        var pending = context("LOCAL"); pending.setPendingPointId(2L);
        var compactor = compactor(model);
        String summary = compactor.summarizePoint(session(), 2L, input);
        assertThat(compactor.compact(session(), turn, pending)).isEqualTo(turn.getPreparedContextJson());
        assertThat(calls.get()).isEqualTo(1);
        var applied = LearningCompactionPolicy.replacePoint(duringCards, 2L, LearningContextMessages.summary(summary, 2L, "POINT"));
        assertThat(applied).hasSize(2);
        assertThat(applied.getFirst().getTextContent()).contains("earlier fact");
        assertThat(applied.getLast().getTextContent()).contains("current summary").doesNotContain("card rewrite");
    }

    @Test
    void failedSecondThresholdSummaryDoesNotRepeatFirstSuccessfulCall() {
        cache();
        AtomicInteger calls = new AtomicInteger();
        Model model = model(messages -> {
            int call = calls.incrementAndGet();
            if (call == 2) { return Flux.error(new IllegalStateException("summary failed")); }
            return answer("summary " + call);
        });
        var turn = turn(List.of(message("older fact ".repeat(5000), 1L, 10L),
                message("current fact ".repeat(5000), 2L, 20L), message("recent", 2L, 30L)));
        var compactor = compactor(model);
        assertThatThrownBy(() -> compactor.compact(session(), turn, context("THRESHOLD"))).hasMessage("summary failed");
        var restored = AgentState.fromJsonString(compactor.compact(session(), turn, context("THRESHOLD"))).getContext();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(restored).hasSize(3);
        assertThat(LearningContextMessages.belongsTo(restored.get(1), 2L)).isTrue();
        assertThat(restored.getLast().getTextContent()).isEqualTo("recent");
    }

    @Test
    void thresholdOnlyDoesNotAddBoundaryModelCallBelowBudget() {
        var model = mock(Model.class);
        var turn = turn(List.of(message("fact", 2L, 30L)));
        when(turns.isCards(turn)).thenReturn(true);
        compactor(model).compact(session(), turn, context("THRESHOLD"));
        verifyNoInteractions(model, summaries);
    }

    private void cache() {
        Map<String, LearningCompaction> cache = new HashMap<>();
        when(summaries.find(any(), anyString(), anyString())).thenAnswer(i -> cache.get(i.getArgument(1) + "/" + i.getArgument(2)));
        when(summaries.save(any(), anyString(), anyString(), nullable(Long.class), anyString())).thenAnswer(i -> {
            var saved = new LearningCompaction(); saved.setSummaryText(i.getArgument(4));
            cache.put(i.getArgument(1) + "/" + i.getArgument(2), saved); return saved;
        });
    }
    private Model model(java.util.function.Function<List<Msg>, Flux<ChatResponse>> completion) {
        return new Model() {
            public String getModelName() { return "test-only"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) { return completion.apply(messages); }
        };
    }
    private Flux<ChatResponse> answer(String text) {
        return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).finishReason("stop").build());
    }
    private LearningConversationCompactor compactor(Model model) {
        return new LearningConversationCompactor(model, new LearningConversationProperties(8000,700,3000,5,60,"LOCAL"), turns, summaries, traces, new ObjectMapper());
    }
    private Msg message(String text, Long point, Long turn) {
        return LearningContextMessages.tag(Msg.builder().role(MsgRole.USER).textContent(text).build(), point, turn);
    }
    private LearningTurn turn(List<Msg> messages) {
        var t = new LearningTurn(); t.setId(30L); t.setUserId(1L); t.setSessionId(10L); t.setKnowledgePointId(2L); t.setTraceId("trace");
        t.setPreparedContextJson(LearningContextMessages.stateJson("1", "s", messages)); return t;
    }
    private LearningSession session() {
        var s = new LearningSession(); s.setId(10L); s.setUserId(1L); s.setAgentscopeSessionId("s"); s.setLearningGoal("goal"); return s;
    }
    private LearningContext context(String strategy) { var c = new LearningContext(); c.setCompressionStrategy(strategy); return c; }
}
