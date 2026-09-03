package com.studyagent.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
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

        persistence.replaceChunks(10L, List.of(parent, child));

        verify(chunkMapper).delete(any(Wrapper.class));
        verify(chunkMapper, times(2)).insert(any(DocumentChunk.class));
        verify(documentMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void markFailedRequiresNewTransaction() throws Exception {
        Method method = DocumentPipelinePersistence.class.getMethod(
                "markFailed", Long.class, PipelineStatus.class, Throwable.class);

        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    private DocumentPipelinePersistence persistence(
            DocumentMapper documentMapper,
            DocumentChunkMapper chunkMapper
    ) {
        return new DocumentPipelinePersistence(documentMapper, mock(FileRecordMapper.class), chunkMapper);
    }
}
