package com.studyagent.agent.integration;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AgentScopeModelConfiguration;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class LearningContextCompactor {

    static final String SUMMARY_PROMPT = """
            Summarize this completed knowledge point for continuing the learning session.
            Preserve the learning goal, mastered points, misconceptions, source chunk ids,
            and context needed for the next knowledge point. Do not invent facts.
            <messages>
            {messages}
            </messages>
            """;

    private final HarnessAgent harnessAgent;
    private final ConversationCompactor compactor;

    public LearningContextCompactor(
            HarnessAgent harnessAgent,
            @Qualifier(AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME) Model model) {
        this.harnessAgent = harnessAgent;
        this.compactor = new ConversationCompactor(model, null);
    }

    public void compact(RuntimeContext context) {
        AgentState state = harnessAgent.getDelegate().getAgentState(context);
        List<io.agentscope.core.message.Msg> current = List.copyOf(state.getContext());
        Optional<List<io.agentscope.core.message.Msg>> compacted = compactor.compactIfNeeded(
                        context,
                        current,
                        oneOffConfig(),
                        harnessAgent.getName(),
                        harnessAgent.getAgentId())
                .block();
        if (compacted == null || compacted.isEmpty()) {
            throw new BusinessException("知识点完成后上下文未按 one-off 契约压缩");
        }
        state.contextMutable().clear();
        state.contextMutable().addAll(compacted.get());
        harnessAgent.getDelegate().saveAgentState(context);
    }

    static CompactionConfig oneOffConfig() {
        return CompactionConfig.builder()
                .triggerMessages(1)
                .keepMessages(1)
                .summaryPrompt(SUMMARY_PROMPT)
                .flushBeforeCompact(false)
                .offloadBeforeCompact(false)
                .build();
    }
}
