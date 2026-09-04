package com.studyagent.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.governance.KnowledgeSearchRetryExecutor;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
import com.studyagent.rag.retrieval.RetrievalHit;
import com.studyagent.rag.retrieval.RetrievalMode;
import com.studyagent.rag.retrieval.RetrievalService;
import com.studyagent.rag.retrieval.RetrievalStrategy;
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
    private RetrievalService retrievalService;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(
                retrievalService,
                new KnowledgeSearchRetryExecutor(),
                new RagProperties(2, 900, 120, 2400, 240, 6, 6, 60),
                new ObjectMapper());
    }

    @Test
    void exposesOnlyQueryAndUsesServerScopeForSingleKnowledgeBaseRetrieval() {
        when(retrievalService.retrieve(
                        RetrievalMode.BM25, "11", "22", "多态", null, 6, 2))
                .thenReturn(List.of(new RetrievalHit(
                        "chunk-1", "parent-1", "多态内容", null, 1.5, RetrievalStrategy.BM25)));

        ToolResultBlock result = tool.callAsync(call(Map.of("query", "多态"))).block();

        verify(retrievalService).retrieve(
                eq(RetrievalMode.BM25), eq("11"), eq("22"), eq("多态"), isNull(), eq(6), eq(2));
        assertThat(((TextBlock) result.getOutput().getFirst()).getText())
                .contains("\"query\":\"多态\"")
                .contains("chunk-1")
                .contains("多态内容");

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

    private ToolCallParam call(Map<String, Object> input) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("11")
                .sessionId("session-1")
                .put(AgentInvocationScope.class, new AgentInvocationScope(11L, 22L, 33L))
                .build();
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
