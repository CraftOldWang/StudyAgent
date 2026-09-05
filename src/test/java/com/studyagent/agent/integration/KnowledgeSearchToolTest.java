package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.governance.KnowledgeSearchRetryExecutor;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.rag.retrieval.KnowledgeRetrievalService;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import com.studyagent.rag.retrieval.RetrievalHit;
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
class KnowledgeSearchToolTest {

    @Mock
    private KnowledgeRetrievalService knowledgeRetrievalService;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(
                knowledgeRetrievalService,
                new KnowledgeSearchRetryExecutor(),
                new ObjectMapper());
    }

    @Test
    void exposesOnlyQueryAndUsesServerScopeForSingleKnowledgeBaseRetrieval() {
        KnowledgeSearchResponse response = new KnowledgeSearchResponse(
                "多态",
                null,
                List.of(new KnowledgeSearchResponse.Result(
                        "chunk-1",
                        "多态内容",
                        new RetrievalHit.Provenance("document-1", "Java 基础", "{\"page\":1}"),
                        1.5)));
        when(knowledgeRetrievalService.search(11L, 22L, "多态")).thenReturn(response);

        ToolResultBlock result = tool.callAsync(call(Map.of("query", "多态"))).block();

        verify(knowledgeRetrievalService).search(11L, 22L, "多态");
        assertThat(((TextBlock) result.getOutput().getFirst()).getText())
                .contains("\"query\":\"多态\"")
                .contains("chunk-1")
                .contains("多态内容")
                .contains("Java 基础");

        assertThat(tool.getParameters()).containsEntry("type", "object");
        assertThat(tool.getParameters()).containsEntry("required", List.of("query"));
        assertThat(tool.getParameters()).containsEntry("additionalProperties", false);
        assertThat(((Map<?, ?>) tool.getParameters().get("properties")).keySet())
                .isEqualTo(Set.of("query"));
    }

    @Test
    void rejectsMissingTypedScopeBeforeRetrieval() {
        ToolCallParam call = ToolCallParam.builder()
                .input(Map.of("query", "多态"))
                .runtimeContext(RuntimeContext.builder().userId("11").sessionId("s").build())
                .build();

        assertThatThrownBy(() -> tool.callAsync(call).block())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void retainsChunkIdsAcrossMultipleSearchCallsInOneAgentTurn() {
        when(knowledgeRetrievalService.search(11L, 22L, "first"))
                .thenReturn(response("first", "chunk-1"));
        when(knowledgeRetrievalService.search(11L, 22L, "second"))
                .thenReturn(response("second", "chunk-2"));
        RuntimeContext context = scopedContext();

        tool.callAsync(call(Map.of("query", "first"), context)).block();
        tool.callAsync(call(Map.of("query", "second"), context)).block();

        assertThat(context.get(KnowledgeSearchExecution.class).retrievedChunkIds())
                .containsExactlyInAnyOrder("chunk-1", "chunk-2");
    }

    private KnowledgeSearchResponse response(String query, String chunkId) {
        return new KnowledgeSearchResponse(
                query,
                null,
                List.of(new KnowledgeSearchResponse.Result(chunkId, "content", null, 1.0)));
    }

    private ToolCallParam call(Map<String, Object> input) {
        return call(input, scopedContext());
    }

    private RuntimeContext scopedContext() {
        return RuntimeContext.builder()
                .userId("11")
                .sessionId("session-1")
                .put(KnowledgeSearchScope.class, new KnowledgeSearchScope(11L, 22L))
                .build();
    }

    private ToolCallParam call(Map<String, Object> input, RuntimeContext context) {
        ToolUseBlock toolUseBlock = ToolUseBlock.builder()
                .id("call-1")
                .name(KnowledgeSearchTool.TOOL_NAME)
                .input(input)
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(toolUseBlock)
                .input(input)
                .runtimeContext(context)
                .build();
    }
}
