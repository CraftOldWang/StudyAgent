package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LearningTransitionToolChoiceHookTest {

    private final LearningTransitionToolChoiceHook hook = new LearningTransitionToolChoiceHook();
    private final ReActAgent agent = mock(ReActAgent.class);
    private RuntimeContext context;

    @BeforeEach
    void setUp() {
        context = RuntimeContext.builder().userId("1").sessionId("session-1").build();
    }

    @Test
    void doesNotForceTransitionBeforeKnowledgeSearchReturnsHits() {
        context.put(KnowledgeSearchExecution.class, new KnowledgeSearchExecution());
        context.put(LearningTransitionIntent.class, transitionIntent());

        PreReasoningEvent event = event();
        apply(event);

        assertThat(event.getEffectiveGenerateOptions()).isNull();
    }

    @Test
    void forcesTransitionAfterHitsAndPreservesExistingGenerateOptions() {
        context.put(KnowledgeSearchExecution.class, searchWithHit());
        context.put(LearningTransitionIntent.class, transitionIntent());

        PreReasoningEvent event = event(GenerateOptions.builder()
                .maxTokens(1800)
                .toolChoice(new ToolChoice.Auto())
                .build());
        apply(event);

        assertThat(event.getEffectiveGenerateOptions().getMaxTokens()).isEqualTo(1800);
        assertThat(event.getEffectiveGenerateOptions().getToolChoice())
                .isEqualTo(new ToolChoice.Specific(LearningStateTransitionTool.TOOL_NAME));
    }

    @Test
    void restoresAutomaticChoiceAfterValidTransitionRequest() {
        LearningTransitionIntent intent = transitionIntent();
        intent.request(KnowledgePointStatus.QUIZZING);
        context.put(KnowledgeSearchExecution.class, searchWithHit());
        context.put(LearningTransitionIntent.class, intent);

        PreReasoningEvent event = event();
        apply(event);

        assertThat(event.getEffectiveGenerateOptions()).isNull();
    }

    @Test
    void doesNotForceTransitionForQuestionTurn() {
        context.put(KnowledgeSearchExecution.class, searchWithHit());
        context.put(
                LearningTransitionIntent.class,
                new LearningTransitionIntent(KnowledgePointStatus.QUIZZING, null));

        PreReasoningEvent event = event();
        apply(event);

        assertThat(event.getEffectiveGenerateOptions()).isNull();
    }

    private PreReasoningEvent event() {
        return event(null);
    }

    private PreReasoningEvent event(GenerateOptions options) {
        return new PreReasoningEvent(agent, "reasoning-1", options, List.of());
    }

    private void apply(PreReasoningEvent event) {
        hook.onEvent(event)
                .contextWrite(contextView -> contextView.put(
                        io.agentscope.core.agent.AgentBase.RUNTIME_CONTEXT_KEY,
                        context))
                .block();
    }

    private LearningTransitionIntent transitionIntent() {
        return new LearningTransitionIntent(
                KnowledgePointStatus.EXPLAINING,
                KnowledgePointStatus.QUIZZING);
    }

    private KnowledgeSearchExecution searchWithHit() {
        KnowledgeSearchResponse.Result hit = new KnowledgeSearchResponse.Result(
                "chunk-1", "content", null, 1.0);
        return new KnowledgeSearchExecution(
                new KnowledgeSearchResponse("query", null, List.of(hit)));
    }
}
