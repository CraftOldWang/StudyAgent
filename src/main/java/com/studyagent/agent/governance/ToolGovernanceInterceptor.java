package com.studyagent.agent.governance;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在 AgentScope 执行工具前强制实施 StudyAgent 的写卡条数上限。
 */
public final class ToolGovernanceInterceptor implements MiddlewareBase {

    static final String REVIEW_CARD_WRITE = "review_card_write";
    static final String DRAFTS_ARGUMENT = "drafts";
    static final int MAX_REVIEW_CARDS_PER_CALL = 5;

    private static final Logger log = LoggerFactory.getLogger(ToolGovernanceInterceptor.class);

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> governedCalls = input.toolCalls().stream()
                .map(this::limitReviewCardWrite)
                .toList();
        return next.apply(new ActingInput(governedCalls));
    }

    private ToolUseBlock limitReviewCardWrite(ToolUseBlock toolCall) {
        if (!REVIEW_CARD_WRITE.equals(toolCall.getName())) {
            return toolCall;
        }

        Object draftsArgument = toolCall.getInput().get(DRAFTS_ARGUMENT);
        if (!(draftsArgument instanceof List<?> drafts)
                || drafts.size() <= MAX_REVIEW_CARDS_PER_CALL) {
            return toolCall;
        }

        Map<String, Object> limitedInput = new LinkedHashMap<>(toolCall.getInput());
        limitedInput.put(
                DRAFTS_ARGUMENT,
                new ArrayList<>(drafts.subList(0, MAX_REVIEW_CARDS_PER_CALL)));
        log.warn(
                "review_card_write 单次请求 {} 张复习卡，已截断至 5 张",
                drafts.size());

        return ToolUseBlock.builder()
                .id(toolCall.getId())
                .name(toolCall.getName())
                .input(limitedInput)
                .content(toolCall.getContent())
                .metadata(toolCall.getMetadata())
                .state(toolCall.getState())
                .build();
    }
}
