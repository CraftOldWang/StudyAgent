package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearningContextMessagesTest {
    @Test
    void localReplacementPreservesEarlierSummaryAndToolPairsAndRoundTrips() {
        Msg old = LearningContextMessages.summary("earlier fact", 1L, "POINT");
        List<Msg> current = exchange(2L, 20L);
        List<Msg> all = new java.util.ArrayList<>(List.of(old)); all.addAll(current);
        LearningContextMessages.requirePaired(all);
        var selected = LearningCompactionPolicy.point(all, 2L);
        assertThat(selected).containsExactlyElementsOf(current);
        var after = LearningCompactionPolicy.replacePoint(all, 2L, LearningContextMessages.summary("current fact", 2L, "POINT"));
        assertThat(after).hasSize(2).first().isSameAs(old);
        var recovered = AgentState.fromJsonString(LearningContextMessages.stateJson("1", "session", after));
        assertThat(recovered.getContext()).hasSize(2);
        assertThat(LearningContextMessages.belongsTo(recovered.getContext().getLast(), 2L)).isTrue();
    }

    @Test
    void rejectsToolResultWithoutItsUse() {
        assertThatThrownBy(() -> LearningContextMessages.requirePaired(List.of(exchange(2L, 20L).getLast())))
                .hasMessageContaining("工具调用");
    }

    @Test
    void thresholdKeepsLatestWholeTurnAndSeparatesCurrentPointFromOlderHistory() {
        var messages = new java.util.ArrayList<Msg>();
        messages.add(LearningContextMessages.summary("old", 1L, "POINT"));
        messages.addAll(exchange(2L, 20L)); messages.addAll(exchange(2L, 21L));
        var selection = LearningCompactionPolicy.threshold(messages, 2L, 21L, 8000);
        assertThat(selection.older()).hasSize(1);
        assertThat(selection.current()).hasSize(2);
        assertThat(selection.keep()).hasSize(2);
        LearningContextMessages.requirePaired(selection.current());
        LearningContextMessages.requirePaired(selection.keep());
    }

    @Test
    void quizContextNeverRestoresStoredAnswerButRetainsPairedIds() {
        var use = ToolUseBlock.builder().id("quiz-call").name("learning_quiz_publish").input(Map.of("questions", List.of(
                Map.of("question", "Q", "options", List.of("one", "two", "three", "four"), "correctAnswer", "two",
                        "explanation", "private explanation", "sourceChunkId", "source")))).build();
        var messages = List.of(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(use)).build(),
                Msg.builder().role(MsgRole.TOOL).content(List.of(ToolResultBlock.of("quiz-call", "learning_quiz_publish", TextBlock.builder().text("accepted").build()))).build());
        var safe = LearningContextMessages.withoutQuizAnswers(messages);
        LearningContextMessages.requirePaired(safe);
        String json = LearningContextMessages.stateJson("1", "s", safe);
        assertThat(json).doesNotContain("correctAnswer", "private explanation").contains("source");
        assertThat(LearningContextMessages.stateJson("1", "s", messages)).contains("correctAnswer");
    }

    private List<Msg> exchange(Long point, Long turn) {
        String id = "tool-" + turn;
        return List.of(LearningContextMessages.tag(Msg.builder().role(MsgRole.ASSISTANT).content(List.of(
                        ToolUseBlock.builder().id(id).name("knowledge_search").input(Map.of("query", "fact")).build())).build(), point, turn),
                LearningContextMessages.tag(Msg.builder().role(MsgRole.TOOL).content(List.of(
                        ToolResultBlock.of(id, "knowledge_search", TextBlock.builder().text("fact").build()))).build(), point, turn));
    }
}
