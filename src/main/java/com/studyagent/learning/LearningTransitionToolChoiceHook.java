package com.studyagent.learning;

import com.studyagent.agent.integration.KnowledgeSearchExecution;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import reactor.core.publisher.Mono;

public final class LearningTransitionToolChoiceHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent reasoningEvent)) {
            return Mono.just(event);
        }
        return Mono.deferContextual(contextView -> {
            RuntimeContext context = contextView.getOrDefault(
                    AgentBase.RUNTIME_CONTEXT_KEY,
                    null);
            applyToolChoice(reasoningEvent, context);
            return Mono.just(event);
        });
    }

    private void applyToolChoice(PreReasoningEvent event, RuntimeContext context) {
        if (context == null) {
            return;
        }
        KnowledgeSearchExecution search = context.get(KnowledgeSearchExecution.class);
        LearningTransitionIntent intent = context.get(LearningTransitionIntent.class);
        if (search == null || search.hits().isEmpty()
                || intent == null || !intent.requiresTransitionRequest()) {
            return;
        }
        GenerateOptions requiredTransition = GenerateOptions.builder()
                .toolChoice(new ToolChoice.Specific(LearningStateTransitionTool.TOOL_NAME))
                .build();
        event.setGenerateOptions(GenerateOptions.mergeOptions(
                requiredTransition,
                event.getEffectiveGenerateOptions()));
    }
}
