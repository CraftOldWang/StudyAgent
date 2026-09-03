package com.studyagent.modules.tool.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.rag.domain.RagReference;
import com.studyagent.modules.rag.domain.RagSearchResult;
import com.studyagent.modules.review.interfaces.ReviewCardResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class SpringAiLearningAgentToolsTest {

    @Mock
    private KnowledgeSearchTool knowledgeSearchTool;

    @Mock
    private ReviewCardWriteTool reviewCardWriteTool;

    @InjectMocks
    private LearningAgentToolContextResolver contextResolver;

    @Test
    void searchKnowledgeShouldUseServerSideContext() {
        SpringAiLearningAgentTools tools = new SpringAiLearningAgentTools(
                knowledgeSearchTool,
                reviewCardWriteTool,
                contextResolver
        );
        ToolContext toolContext = toolContext();
        RagSearchResult searchResult = new RagSearchResult("RRF 是什么", List.of(new RagReference(
                "chunk-10",
                20L,
                30L,
                null,
                1,
                "检索笔记",
                "RRF 用于融合多个召回列表。",
                "rrf",
                0.9d
        )));
        when(knowledgeSearchTool.search(100L, 200L, 1L, List.of(30L), "RRF 是什么"))
                .thenReturn(searchResult);

        SpringAiLearningAgentTools.KnowledgeSearchToolResult result =
                tools.searchKnowledge("RRF 是什么", toolContext);

        assertThat(result.hitCount()).isEqualTo(1);
        assertThat(result.references().getFirst().chunkId()).isEqualTo("chunk-10");
        verify(knowledgeSearchTool).search(100L, 200L, 1L, List.of(30L), "RRF 是什么");
    }

    @Test
    void writeReviewCardsShouldRejectBlankDraftBeforeCallingTool() {
        SpringAiLearningAgentTools tools = new SpringAiLearningAgentTools(
                knowledgeSearchTool,
                reviewCardWriteTool,
                contextResolver
        );

        assertThatThrownBy(() -> tools.writeReviewCards(List.of(new SpringAiLearningAgentTools.ReviewCardDraftArgument(
                30L,
                20L,
                "",
                "答案",
                List.of("agent"),
                null,
                List.of(10L)
        )), toolContext()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正面");
    }

    @Test
    void writeReviewCardsShouldUseServerSideContext() {
        SpringAiLearningAgentTools tools = new SpringAiLearningAgentTools(
                knowledgeSearchTool,
                reviewCardWriteTool,
                contextResolver
        );
        List<SpringAiLearningAgentTools.ReviewCardDraftArgument> drafts = List.of(
                new SpringAiLearningAgentTools.ReviewCardDraftArgument(
                        30L,
                        20L,
                        "RRF 用来解决什么问题？",
                        "用于融合 BM25 和向量检索等多个召回列表。",
                        List.of("rag"),
                        null,
                        List.of(10L)
                )
        );
        when(reviewCardWriteTool.writeCards(eq(100L), eq(200L), eq(1L), eq(List.of(30L)), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new ReviewCardResponse(
                        300L,
                        30L,
                        20L,
                        200L,
                        "RRF 用来解决什么问题？",
                        "用于融合 BM25 和向量检索等多个召回列表。",
                        "[\"rag\"]",
                        "ACTIVE",
                        "NEW",
                        LocalDateTime.now(),
                        null,
                        0.0d,
                        0.0d,
                        0,
                        0,
                        0,
                        0
                )));

        SpringAiLearningAgentTools.ReviewCardWriteToolResult result =
                tools.writeReviewCards(drafts, toolContext());

        assertThat(result.cardCount()).isEqualTo(1);
        assertThat(result.cards().getFirst().cardId()).isEqualTo(300L);
    }

    private ToolContext toolContext() {
        LearningAgentToolContext context = new LearningAgentToolContext(100L, 200L, 1L, List.of(30L));
        return new ToolContext(context.toToolContextMap());
    }
}
