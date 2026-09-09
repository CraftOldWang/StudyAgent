package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.agent.integration.KnowledgeSearchScope;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.rag.retrieval.SourceReader;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearningSourceToolTest {
    private final SourceReader reader = mock(SourceReader.class);
    private final LearningSourceTool tool = new LearningSourceTool(reader, List.of("planned"), new ObjectMapper());

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("11").sessionId("s")
                .put(KnowledgeSearchScope.class, new KnowledgeSearchScope(11L, 22L))
                .put(KnowledgeSearchExecution.class, new KnowledgeSearchExecution()).build();
    }
    private ToolCallParam call(String id, RuntimeContext runtime) {
        return ToolCallParam.builder().input(Map.of("chunkId", id)).runtimeContext(runtime).build();
    }
    @Test void readsOnlyPlannedSourceWithServerScopeAndRecordsActualEvidence() {
        var runtime = context();
        when(reader.read(11L, 22L, "planned")).thenReturn(new SourceReader.Source("planned", 33L, "Lecture", "page 2", "Actual original text"));
        var result = tool.callAsync(call("planned", runtime)).block();
        assertThat(((TextBlock) result.getOutput().getFirst()).getText()).contains("Actual original text", "Lecture", "page 2");
        assertThat(runtime.get(KnowledgeSearchExecution.class).retrievedChunkIds()).containsExactly("planned");
        verify(reader).read(11L, 22L, "planned");
        assertThat(((Map<?, ?>) tool.getParameters().get("properties")).keySet()).isEqualTo(java.util.Set.of("chunkId"));
    }
    @Test void rejectsUnplannedOrMissingScopeBeforeAccessingSource() {
        assertThatThrownBy(() -> tool.callAsync(call("foreign", context())).block()).hasMessageContaining("当前知识点");
        assertThatThrownBy(() -> tool.callAsync(call("planned", RuntimeContext.builder().userId("11").sessionId("s").build())).block())
                .hasMessageContaining("scope");
        verifyNoInteractions(reader);
    }
    @Test void sourceOwnershipOrMissingSourceFailureCannotBecomeReadEvidence() {
        var runtime = context();
        when(reader.read(11L, 22L, "planned")).thenThrow(new BusinessException(404, "引用资料不属于当前资料库"));
        assertThatThrownBy(() -> tool.callAsync(call("planned", runtime)).block()).hasMessageContaining("不属于");
        assertThat(runtime.get(KnowledgeSearchExecution.class).invoked()).isFalse();
    }
}
