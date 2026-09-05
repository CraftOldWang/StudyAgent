package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class KnowledgePointLifecycleTest {

    private final KnowledgePointLifecycle lifecycle = new KnowledgePointLifecycle();

    @Test
    void advancesOneStepThroughTheLifecycle() {
        assertThat(lifecycle.advance(KnowledgePointStatus.NEW, KnowledgePointStatus.EXPLAINING))
                .isEqualTo(KnowledgePointStatus.EXPLAINING);
        assertThat(lifecycle.advance(KnowledgePointStatus.EXPLAINING, KnowledgePointStatus.QUIZZING))
                .isEqualTo(KnowledgePointStatus.QUIZZING);
        assertThat(lifecycle.advance(KnowledgePointStatus.QUIZZING, KnowledgePointStatus.CARD_GENERATING))
                .isEqualTo(KnowledgePointStatus.CARD_GENERATING);
        assertThat(lifecycle.advance(KnowledgePointStatus.CARD_GENERATING, KnowledgePointStatus.COMPLETED))
                .isEqualTo(KnowledgePointStatus.COMPLETED);
    }

    @Test
    void rejectsSkippedState() {
        assertThatIllegalStateException()
                .isThrownBy(() -> lifecycle.advance(KnowledgePointStatus.NEW, KnowledgePointStatus.QUIZZING))
                .withMessageContaining("only advance from NEW to EXPLAINING");
    }

    @Test
    void rejectsRepeatedOrBackwardState() {
        assertThatIllegalStateException()
                .isThrownBy(() -> lifecycle.advance(KnowledgePointStatus.EXPLAINING, KnowledgePointStatus.EXPLAINING));
        assertThatIllegalStateException()
                .isThrownBy(() -> lifecycle.advance(KnowledgePointStatus.QUIZZING, KnowledgePointStatus.EXPLAINING));
    }

    @Test
    void rejectsAdvancingTerminalState() {
        assertThatIllegalStateException()
                .isThrownBy(() -> lifecycle.advance(KnowledgePointStatus.COMPLETED, KnowledgePointStatus.COMPLETED))
                .withMessage("COMPLETED is a terminal knowledge point state");
    }

    @Test
    void rejectsMissingState() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.advance(null, KnowledgePointStatus.EXPLAINING));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.advance(KnowledgePointStatus.NEW, null));
    }
}
