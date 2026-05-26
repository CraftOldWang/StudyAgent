package com.studyagent.modules.tool.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.rag.application.RagService;
import com.studyagent.modules.tool.domain.ToolCallRecord;
import com.studyagent.modules.tool.infrastructure.ToolCallRecordMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock
    private RagService ragService;

    @Mock
    private ToolCallRecordMapper toolCallRecordMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private KnowledgeSearchTool knowledgeSearchTool;

    @Test
    void searchShouldRejectEmptyKnowledgeScopeBeforeCallingRag() {
        assertThatThrownBy(() -> knowledgeSearchTool.search(1L, 2L, 1L, List.of(), "问题"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库范围");

        verify(ragService, never()).search(any(), any(), any());
        verify(toolCallRecordMapper).insert(any(ToolCallRecord.class));
        verify(toolCallRecordMapper).updateById(any(ToolCallRecord.class));
    }
}
