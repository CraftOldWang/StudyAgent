package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.KnowledgePointMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.ReviewCard;
import com.studyagent.review.ReviewCardService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewCardWriteToolTest {

    @Mock
    private ReviewCardService reviewCardService;

    @Mock
    private KnowledgePointMapper knowledgePointMapper;

    private ReviewCardWriteTool tool;

    @BeforeEach
    void setUp() {
        tool = new ReviewCardWriteTool(reviewCardService, knowledgePointMapper, new ObjectMapper());
    }

    @Test
    void exposesOnlyDraftContentAndBindsAllIdsFromTypedScope() {
        when(knowledgePointMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenReturn(point("CARD_GENERATING"));
        ReviewCard card = new ReviewCard();
        card.setId(101L);
        card.setFront("什么是多态？");
        card.setBack("同一接口的多种实现。");
        card.setSourceChunkId("chunk-1");
        when(reviewCardService.writeBatch(eq(11L), eq(33L), eq(22L), anyList()))
                .thenReturn(List.of(card));

        Map<String, Object> input = Map.of(
                "drafts", List.of(Map.of(
                        "front", "什么是多态？",
                        "back", "同一接口的多种实现。",
                        "sourceChunkId", "chunk-1",
                        "userId", 999L,
                        "knowledgeBaseId", 999L)));
        ToolResultBlock result = tool.callAsync(call(input)).block();

        verify(reviewCardService).writeBatch(eq(11L), eq(33L), eq(22L), anyList());
        assertThat(((TextBlock) result.getOutput().getFirst()).getText())
                .contains("\"cardCount\":1")
                .contains("chunk-1");

        Map<?, ?> properties = (Map<?, ?>) tool.getParameters().get("properties");
        assertThat(properties.keySet()).isEqualTo(Set.of("drafts"));
        Map<?, ?> drafts = (Map<?, ?>) properties.get("drafts");
        Map<?, ?> items = (Map<?, ?>) drafts.get("items");
        assertThat(((Map<?, ?>) items.get("properties")).keySet())
                .isEqualTo(Set.of("front", "back", "sourceChunkId"));
    }

    @Test
    void rejectsWriteOutsideCardGeneratingState() {
        when(knowledgePointMapper.selectOne(org.mockito.ArgumentMatchers.any()))
                .thenReturn(point("QUIZZING"));

        assertThatThrownBy(() -> tool.callAsync(call(Map.of(
                        "drafts", List.of(Map.of("front", "front", "back", "back")))))
                .block())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CARD_GENERATING");

        verify(reviewCardService, never()).writeBatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anyList());
    }

    private KnowledgePoint point(String status) {
        KnowledgePoint point = new KnowledgePoint();
        point.setId(33L);
        point.setUserId(11L);
        point.setStatus(status);
        return point;
    }

    private ToolCallParam call(Map<String, Object> input) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("11")
                .sessionId("session-1")
                .put(AgentInvocationScope.class, new AgentInvocationScope(11L, 22L, 33L))
                .build();
        ToolUseBlock toolUseBlock = ToolUseBlock.builder()
                .id("call-1")
                .name(ReviewCardWriteTool.TOOL_NAME)
                .input(input)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(toolUseBlock)
                .input(input)
                .runtimeContext(context)
                .build();
    }
}
