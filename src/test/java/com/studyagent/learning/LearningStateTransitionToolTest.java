package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearningStateTransitionToolTest {

    private final LearningStateTransitionTool tool = new LearningStateTransitionTool();

    @Test
    void acceptsOnlyServerExpectedAdjacentTransition() {
        LearningTransitionIntent intent = new LearningTransitionIntent(
                KnowledgePointStatus.EXPLAINING, KnowledgePointStatus.QUIZZING);

        assertThat(tool.callAsync(call(intent, "QUIZZING")).block().getOutput()).isNotEmpty();
        intent.requireRequested();
    }

    @Test
    void rejectsSkipAndQuestionTurnMutation() {
        LearningTransitionIntent skip = new LearningTransitionIntent(
                KnowledgePointStatus.NEW, KnowledgePointStatus.QUIZZING);
        assertThatThrownBy(() -> tool.callAsync(call(skip, "QUIZZING")).block())
                .isInstanceOf(IllegalStateException.class);

        LearningTransitionIntent question = new LearningTransitionIntent(KnowledgePointStatus.QUIZZING, null);
        assertThatThrownBy(() -> tool.callAsync(call(question, "CARD_GENERATING")).block())
                .isInstanceOf(BusinessException.class);
    }

    private ToolCallParam call(LearningTransitionIntent intent, String target) {
        RuntimeContext context = RuntimeContext.builder().userId("1").sessionId("s").build();
        context.put(LearningTransitionIntent.class, intent);
        ToolUseBlock use = ToolUseBlock.builder()
                .id("call-1")
                .name(LearningStateTransitionTool.TOOL_NAME)
                .input(Map.of("target", target))
                .build();
        return ToolCallParam.builder()
                .runtimeContext(context)
                .toolUseBlock(use)
                .input(Map.of("target", target))
                .build();
    }
}
