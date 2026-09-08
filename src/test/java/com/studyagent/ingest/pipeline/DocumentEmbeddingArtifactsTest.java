package com.studyagent.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.AiModelProperties;
import com.studyagent.mapper.EmbeddingArtifactMapper;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.EmbeddingArtifact;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class DocumentEmbeddingArtifactsTest {
    private final EmbeddingArtifactMapper mapper = mock(EmbeddingArtifactMapper.class);
    private final EmbeddingService provider = mock(EmbeddingService.class);
    private final DocumentEmbeddingArtifacts artifacts = new DocumentEmbeddingArtifacts(mapper, provider,
            new AiModelProperties(new AiModelProperties.Embedding("dashscope", "test-model", 2, "unused", "http://localhost"), null),
            new ObjectMapper());

    @Test
    void cachesEachCompletedItemAndReadsItWithoutAnotherProviderCall() {
        when(provider.embed("text", EmbeddingPurpose.DOCUMENT)).thenReturn(new float[]{0.1f, 0.2f});
        artifacts.loadOrCreate(1L, chunk());
        ArgumentCaptor<EmbeddingArtifact> saved = ArgumentCaptor.forClass(EmbeddingArtifact.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(1L);
        assertThat(saved.getValue().getContentHash()).isEqualTo("hash");
        assertThat(saved.getValue().getModel()).isEqualTo("test-model");
        assertThat(saved.getValue().getDimensions()).isEqualTo(2);
        when(mapper.selectOne(any())).thenReturn(saved.getValue());
        assertThat(artifacts.loadOrCreate(1L, chunk())).containsExactly(0.1f, 0.2f);
        verify(provider, times(1)).embed("text", EmbeddingPurpose.DOCUMENT);
    }

    @Test
    void concurrentDuplicateUsesCommittedArtifact() {
        EmbeddingArtifact winner = new EmbeddingArtifact();
        winner.setVectorJson("[0.3,0.4]");
        when(mapper.selectOne(any())).thenReturn(null, winner);
        when(provider.embed("text", EmbeddingPurpose.DOCUMENT)).thenReturn(new float[]{0.1f, 0.2f});
        doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any(EmbeddingArtifact.class));
        assertThat(artifacts.loadOrCreate(1L, chunk())).containsExactly(0.3f, 0.4f);
    }

    @Test
    void damagedArtifactFailsExplicitlyWithoutSilentlyRecomputing() {
        EmbeddingArtifact damaged = new EmbeddingArtifact();
        damaged.setVectorJson("[0.1]");
        when(mapper.selectOne(any())).thenReturn(damaged);
        assertThatThrownBy(() -> artifacts.loadOrCreate(1L, chunk())).hasMessageContaining("维度错误");
        verifyNoInteractions(provider);
    }

    private DocumentChunk chunk() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentId(10L);
        chunk.setChunkId("child");
        chunk.setContent("text");
        chunk.setContentHash("hash");
        return chunk;
    }
}
