package com.studyagent.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import org.junit.jupiter.api.Test;

class DocumentRetryServiceTest {
    @Test
    void requeuesOwnedFailedDocumentWithoutCreatingAnotherDocument() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentIndexProducer producer = mock(DocumentIndexProducer.class);
        Document failed = new Document();
        failed.setPipelineStatus("FAILED");
        when(mapper.selectOne(any())).thenReturn(failed);
        new DocumentRetryService(mapper, producer).retry(1L, 10L);
        verify(producer).send(10L, 1L);
    }

    @Test
    void rejectsMissingOrNonfailedDocumentBeforeEnqueue() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        DocumentIndexProducer producer = mock(DocumentIndexProducer.class);
        Document completed = new Document();
        completed.setPipelineStatus("INDEXED");
        when(mapper.selectOne(any())).thenReturn(null, completed);
        var service = new DocumentRetryService(mapper, producer);
        assertThatThrownBy(() -> service.retry(2L, 10L)).hasMessageContaining("无访问权限");
        assertThatThrownBy(() -> service.retry(1L, 10L)).hasMessageContaining("仅失败");
        verifyNoInteractions(producer);
    }
}
