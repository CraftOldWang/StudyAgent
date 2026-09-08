package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.rag.web.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

class SourceReaderTest {
    @Test
    void returnsPersistedTextAndLocationOnlyWithinTheOwnedKnowledgeBase() {
        var bases = mock(KnowledgeBaseService.class);
        var chunks = mock(DocumentChunkMapper.class);
        var documents = mock(DocumentMapper.class);
        var source = new DocumentChunk(); source.setChunkId("chunk-1"); source.setDocumentId(3L);
        source.setContent("Saved source text"); source.setSourceLocation("page 2");
        var document = new Document(); document.setId(3L); document.setUserId(1L); document.setKnowledgeBaseId(2L); document.setTitle("Course");
        when(chunks.selectOne(any())).thenReturn(source);
        when(documents.selectById(3L)).thenReturn(document);
        var reader = new SourceReader(bases, chunks, documents);
        assertThat(reader.read(1L, 2L, "chunk-1").content()).isEqualTo("Saved source text");
        verify(bases).requireOwned(1L, 2L);
        document.setKnowledgeBaseId(4L);
        assertThatThrownBy(() -> reader.read(1L, 2L, "chunk-1")).isInstanceOf(BusinessException.class);
        document.setKnowledgeBaseId(2L); document.setUserId(8L);
        assertThatThrownBy(() -> reader.read(1L, 2L, "chunk-1")).isInstanceOf(BusinessException.class);
    }

    @Test
    void unavailableKnowledgeBaseStopsBeforeReadingAnySource() {
        var bases = mock(KnowledgeBaseService.class);
        var chunks = mock(DocumentChunkMapper.class);
        var documents = mock(DocumentMapper.class);
        doThrow(new BusinessException("not owned")).when(bases).requireOwned(1L, 2L);
        assertThatThrownBy(() -> new SourceReader(bases, chunks, documents).read(1L, 2L, "chunk-1")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(chunks, documents);
    }
}
