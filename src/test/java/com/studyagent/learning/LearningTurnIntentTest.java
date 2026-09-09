package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.studyagent.agent.integration.KnowledgeSearchExecution;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LearningTurnIntentTest {
    private final KnowledgeSearchExecution search = mock(KnowledgeSearchExecution.class);
    private final List<QuizQuestionDraft> quiz = IntStream.range(0,5).mapToObj(i ->
            new QuizQuestionDraft("测试题" + i,List.of("甲","乙","丙","丁"),"甲","测试解析","source")).toList();

    private void searched() { when(search.invoked()).thenReturn(true); when(search.retrievedChunkIds()).thenReturn(Set.of("source")); }

    @Test void naturalQuestionDoesNotRequireTransition() {
        var intent = new LearningTurnIntent(KnowledgePointStatus.NEW,search,"这个课程先学什么？",null);
        assertThat(intent.action()).isNull();
        verifyNoInteractions(search);
    }

    @Test void missingRetrievalAndInvalidBatchLeaveNoStagedAction() {
        var intent = new LearningTurnIntent(KnowledgePointStatus.EXPLAINING,search,"开始测验",null);
        assertThatThrownBy(() -> intent.publishQuiz(quiz)).hasMessageContaining("先检索");
        assertThat(intent.action()).isNull();
        searched();
        assertThatThrownBy(() -> intent.publishQuiz(java.util.Collections.nCopies(11, quiz.getFirst()))).hasMessageContaining("1–10");
        assertThat(intent.action()).isNull();
        intent.publishQuiz(quiz);
        assertThat(intent.questions()).hasSize(5);
        assertThat(intent.action()).isEqualTo(LearningTurnIntent.Action.QUIZ);
    }

    @Test void cannotSkipStateOrPerformTwoTransitionsInOneTurn() {
        searched();
        var intent = new LearningTurnIntent(KnowledgePointStatus.NEW,search,"开始学习",null);
        assertThatThrownBy(() -> intent.publishQuiz(quiz)).isInstanceOf(com.studyagent.common.exception.BusinessException.class);
        assertThat(intent.action()).isNull();
        intent.explanationDone();
        assertThatThrownBy(intent::explanationDone).hasMessageContaining("最多");
    }

    @Test void modelCannotChooseAnswersAndServerGradesNumberedUserChoices() {
        var incomplete = new LearningTurnIntent(KnowledgePointStatus.QUIZZING,search,"1.A 2.B",quiz);
        assertThatThrownBy(incomplete::submitQuiz).hasMessageContaining("完整提交");
        assertThat(incomplete.action()).isNull();
        var intent = new LearningTurnIntent(KnowledgePointStatus.QUIZZING,search,"我的答案是1.A 2.B 3.C 4.D 5.A",quiz);
        intent.submitQuiz();
        assertThat(intent.score()).isEqualTo(40);
        assertThat(intent.answers()).containsExactly("甲","乙","丙","丁","甲");
        assertThat(intent.feedback()).hasSize(5);
        verifyNoInteractions(search);
    }

    @Test void rejectsUnretrievedCardSourceAndDuplicateVisibleQuizOptions() {
        searched();
        var intent = new LearningTurnIntent(KnowledgePointStatus.CARD_GENERATING,search,"生成复习卡",null);
        var cards = List.of(new GeneratedCard("一","答","source"),new GeneratedCard("二","答","source"),new GeneratedCard("三","答","foreign"));
        assertThatThrownBy(() -> intent.publishCards(cards)).hasMessageContaining("当前实际检索");
        assertThat(intent.action()).isNull();
        var bad = new java.util.ArrayList<>(quiz);
        bad.set(0,new QuizQuestionDraft("重复选项",List.of("甲"," 甲 ","乙","丙"),"甲","解析","source"));
        assertThatThrownBy(() -> LearningArtifactValidator.quiz(bad,Set.of("source"))).hasMessageContaining("不同");
    }
}
