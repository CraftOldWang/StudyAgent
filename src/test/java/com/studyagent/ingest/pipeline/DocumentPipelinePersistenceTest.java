package com.studyagent.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.config.DocumentPipelineProperties;
import java.time.Duration;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class DocumentPipelinePersistenceTest {

    @Test
    void claimReturnsDocumentOnlyWhenConditionalUpdateWins() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        Document claimed = new Document();
        claimed.setId(10L);
        when(documentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(documentMapper.selectById(10L)).thenReturn(claimed);
        DocumentPipelinePersistence persistence = persistence(documentMapper, mock(DocumentChunkMapper.class));

        assertThat(persistence.claim(10L, true)).isSameAs(claimed);

        verify(documentMapper).selectById(10L);
    }

    @Test
    void replaceChunksUsesOneShortPersistenceBoundary() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        when(documentMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        DocumentPipelinePersistence persistence = persistence(documentMapper, chunkMapper);
        DocumentChunk parent = new DocumentChunk();
        DocumentChunk child = new DocumentChunk();
        DocumentChunk previous = new DocumentChunk();
        previous.setId(50L);
        when(chunkMapper.selectList(any(Wrapper.class))).thenReturn(List.of(previous));

        Document document = new Document();
        document.setId(10L);
        document.setProcessingToken("owner-token");
        persistence.replaceChunks(document, List.of(parent, child), "chunker-test-version");

        verify(chunkMapper).deleteById(50L);
        verify(chunkMapper, never()).delete(any(Wrapper.class));
        verify(chunkMapper, times(2)).insert(any(DocumentChunk.class));
        verify(documentMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void newDocumentDoesNotDeleteAnEmptySecondaryIndexRange() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(chunks.selectList(any(Wrapper.class))).thenReturn(List.of());
        Document document = new Document();
        document.setId(11L);
        document.setProcessingToken("owner");
        persistence(mapper, chunks).replaceChunks(document, List.of(new DocumentChunk()), "v2");
        verify(chunks, never()).delete(any(Wrapper.class));
        verify(chunks, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
        verify(chunks).insert(any(DocumentChunk.class));
    }

    @Test
    void markFailedRequiresNewTransaction() throws Exception {
        Method method = DocumentPipelinePersistence.class.getMethod(
                "markFailed", Document.class, PipelineStatus.class, Throwable.class);

        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    private DocumentPipelinePersistence persistence(
            DocumentMapper documentMapper,
            DocumentChunkMapper chunkMapper
    ) {
        return new DocumentPipelinePersistence(documentMapper, mock(FileRecordMapper.class), chunkMapper,
                new DocumentPipelineProperties(Duration.ofMinutes(5), Duration.ofSeconds(30), 100));
    }

    @Test
    void lostExecutionCannotReplaceChunks() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
        Document document = new Document();
        document.setId(10L);
        document.setProcessingToken("stale-owner");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> persistence(mapper, chunks)
                .replaceChunks(document, List.of(new DocumentChunk()), "v2"))
                .hasMessageContaining("执行权已过期");
        org.mockito.Mockito.verifyNoInteractions(chunks);
        var update = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSegment()).contains("processing_token", "lease_until");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue("stale-owner");
    }
}
