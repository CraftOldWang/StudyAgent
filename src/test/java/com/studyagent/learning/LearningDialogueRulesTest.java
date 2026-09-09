package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LearningDialogueRulesTest {
    @Test void priorReadEvidenceCanBeReusedButOtherPointsAndProseCannotAuthorizeSources() {
        var use = io.agentscope.core.message.ToolUseBlock.builder().id("read").name("knowledge_read")
                .input(java.util.Map.of("chunkId", "source")).build();
        var result = io.agentscope.core.message.ToolResultBlock.of("read", "knowledge_read",
                io.agentscope.core.message.TextBlock.builder().text("{\"hits\":[{\"chunkId\":\"source\",\"content\":\"actual fact\"}]}").build());
        var messages = List.of(
                LearningContextMessages.tag(io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.ASSISTANT).content(List.of(use)).build(), 1L, 2L),
                LearningContextMessages.tag(io.agentscope.core.message.Msg.builder().role(io.agentscope.core.message.MsgRole.TOOL).content(List.of(result)).build(), 1L, 2L),
                LearningContextMessages.summary("source: invented", 1L, "POINT"));
        var sources = LearningContextMessages.retainedSources(messages, 1L, new ObjectMapper());
        assertThat(sources).containsExactly("source");
        assertThat(LearningContextMessages.retainedSources(messages, 9L, new ObjectMapper())).isEmpty();
        var intent = new LearningTurnIntent(KnowledgePointStatus.EXPLAINING, new KnowledgeSearchExecution(), "继续", List.of());
        intent.retainSources(sources);
        var question = new QuizQuestionDraft("q", List.of("a", "b", "c", "d"), "a", "why", "source");
        assertThatThrownBy(() -> intent.publishQuiz(List.of(new QuizQuestionDraft("q", question.options(), "a", "why", "invented"))))
                .hasMessageContaining("实际读取");
        intent.publishQuiz(List.of(question));
        assertThat(intent.action()).isEqualTo(LearningTurnIntent.Action.QUIZ);
    }

    @Test void acceptsVariableQuizSizesButRejectsEleven() {
        var questions = IntStream.range(0, 10).mapToObj(i -> new QuizQuestionDraft("question " + i,
                List.of("a", "b", "c", "d"), "a", "reason", "source")).toList();
        assertThat(LearningArtifactValidator.quiz(questions.subList(0, 3), Set.of("source"))).hasSize(3);
        assertThat(LearningArtifactValidator.quiz(questions, Set.of("source"))).hasSize(10);
        assertThatThrownBy(() -> LearningArtifactValidator.quiz(java.util.Collections.nCopies(11, questions.getFirst()), Set.of("source")))
                .hasMessageContaining("1–10");
        var intent = new LearningTurnIntent(KnowledgePointStatus.QUIZZING, mock(KnowledgeSearchExecution.class), "1.A 2.B 3.A", questions.subList(0, 3));
        intent.submitQuiz();
        assertThat(intent.score()).isEqualTo(67);
        assertThat(QuizAnswerParser.parse("1.A 2.A 3.A 4.A 5.A 6.A 7.A 8.A 9.A 10.A", 10)).isPresent();
        assertThat(QuizAnswerParser.parse("1.A 2.A 3.A 4.A", 3)).isEmpty();
    }

    @Test void cardsRequireUserDrivenStageButCanBeRewrittenWithoutCompletingPoint() {
        var search = mock(KnowledgeSearchExecution.class);
        when(search.invoked()).thenReturn(true); when(search.retrievedChunkIds()).thenReturn(Set.of("source"));
        var intent = new LearningTurnIntent(KnowledgePointStatus.FEEDBACK, search, "没有疑问了，继续", List.of());
        var drafts = List.of(new GeneratedCard("front", "back", "source"));
        assertThatThrownBy(() -> intent.publishCards(drafts)).hasMessageContaining("先调用");
        Runnable begin = mock(Runnable.class); intent.onBeginCards(begin); intent.beginCards();
        verify(begin).run(); intent.publishCards(drafts);
        assertThat(intent.action()).isEqualTo(LearningTurnIntent.Action.CARDS);
        var rewrite = new LearningTurnIntent(KnowledgePointStatus.CARD_GENERATING, search, "重写", List.of());
        rewrite.publishCards(drafts);
        assertThat(rewrite.cards()).hasSize(1);
        assertThatThrownBy(() -> LearningArtifactValidator.cards(java.util.Collections.nCopies(11, drafts.getFirst()), Set.of("source")))
                .hasMessageContaining("1–10");
        assertThatThrownBy(() -> new KnowledgePointLifecycle().advance(KnowledgePointStatus.CARD_GENERATING, KnowledgePointStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void collapsedToolPayloadCannotRevealUnsubmittedQuizAnswers() {
        var mapper = new ObjectMapper();
        var input = java.util.Map.of("questions", List.of(java.util.Map.of("question", "q", "correctAnswer", "secret", "explanation", "reason")));
        var safe = LearningToolDisplay.input(mapper, "learning_quiz_publish", input);
        assertThat(safe.toString()).contains("question").doesNotContain("secret", "reason", "correctAnswer");
    }
}
