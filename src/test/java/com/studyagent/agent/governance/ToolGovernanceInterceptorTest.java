package com.studyagent.agent.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

@ExtendWith(OutputCaptureExtension.class)
class ToolGovernanceInterceptorTest {

    private final ToolGovernanceInterceptor interceptor = new ToolGovernanceInterceptor();

    @Test
    void truncatesOnlyReviewCardWriteToFirstFiveAndLogsWarning(CapturedOutput output) {
        List<Map<String, String>> drafts = drafts(10);
        ToolUseBlock reviewCardWrite = toolCall("review_card_write", Map.of("drafts", drafts));
        ToolUseBlock knowledgeSearch = toolCall(
                "knowledge_search",
                Map.of("drafts", drafts, "query", "Java 多态"));
        AtomicReference<ActingInput> forwarded = new AtomicReference<>();

        interceptor.onActing(null, null, new ActingInput(List.of(reviewCardWrite, knowledgeSearch)), input -> {
                    forwarded.set(input);
                    return Flux.empty();
                })
                .blockLast();

        ToolUseBlock governedReviewCall = forwarded.get().toolCalls().getFirst();
        assertThat(governedReviewCall.getInput().get("drafts"))
                .isEqualTo(drafts.subList(0, 5));
        assertThat(forwarded.get().toolCalls().get(1)).isSameAs(knowledgeSearch);
        assertThat(output).contains("review_card_write 单次请求 10 张复习卡，已截断至 5 张");
    }

    @Test
    void leavesReviewCardWriteAtLimitUnchangedAndDoesNotWarn(CapturedOutput output) {
        ToolUseBlock reviewCardWrite = toolCall("review_card_write", Map.of("drafts", drafts(5)));
        AtomicReference<ActingInput> forwarded = new AtomicReference<>();

        interceptor.onActing(null, null, new ActingInput(List.of(reviewCardWrite)), input -> {
                    forwarded.set(input);
                    return Flux.empty();
                })
                .blockLast();

        assertThat(forwarded.get().toolCalls().getFirst()).isSameAs(reviewCardWrite);
        assertThat(output).doesNotContain("已截断至 5 张");
    }

    private static ToolUseBlock toolCall(String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(name + "-1")
                .name(name)
                .input(input)
                .build();
    }

    private static List<Map<String, String>> drafts(int count) {
        List<Map<String, String>> drafts = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            drafts.add(Map.of("front", "front-" + index, "back", "back-" + index));
        }
        return drafts;
    }
}
