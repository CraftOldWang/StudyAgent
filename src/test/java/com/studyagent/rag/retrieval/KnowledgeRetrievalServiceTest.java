package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.config.RagProperties;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalServiceTest {

    @Test
    void usesQueryEmbeddingAndParentHybridRetrievalWithinServerScope() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        RagProperties properties = new RagProperties(2, 900, 120, 2400, 240, 30, 20, 60);
        float[] queryVector = {0.1f, 0.2f};
        RetrievalHit.Provenance provenance =
                new RetrievalHit.Provenance("document-1", "Java 基础", "{\"page\":1}");
        when(embeddingService.embed("Java 多态", EmbeddingPurpose.QUERY)).thenReturn(queryVector);
        when(retrievalService.retrieve(
                        RetrievalMode.PARENT, "11", "22", "Java 多态", queryVector, 30, 2))
                .thenReturn(List.of(new RetrievalHit(
                        "chunk-1", "parent-1", "父块内容", provenance, 0.9, RetrievalStrategy.RRF)));
        KnowledgeRetrievalService service =
                new KnowledgeRetrievalService(embeddingService, retrievalService, properties);

        KnowledgeSearchResponse response = service.search(11L, 22L, "  Java 多态  ");

        verify(embeddingService).embed("Java 多态", EmbeddingPurpose.QUERY);
        verify(retrievalService).retrieve(
                RetrievalMode.PARENT, "11", "22", "Java 多态", queryVector, 30, 2);
        assertThat(response.query()).isEqualTo("Java 多态");
        assertThat(response.message()).isNull();
        assertThat(response.hits()).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.chunkId()).isEqualTo("chunk-1");
                    assertThat(hit.content()).isEqualTo("父块内容");
                    assertThat(hit.provenance()).isEqualTo(provenance);
                });
    }

    @Test
    void returnsExplicitNoEvidenceMessageWithoutInventingHits() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        RagProperties properties = new RagProperties(2, 900, 120, 2400, 240, 6, 6, 60);
        float[] queryVector = {0.1f};
        when(embeddingService.embed("不存在的主题", EmbeddingPurpose.QUERY)).thenReturn(queryVector);
        when(retrievalService.retrieve(
                        RetrievalMode.PARENT, "11", "22", "不存在的主题", queryVector, 6, 2))
                .thenReturn(List.of());
        KnowledgeRetrievalService service =
                new KnowledgeRetrievalService(embeddingService, retrievalService, properties);

        KnowledgeSearchResponse response = service.search(11L, 22L, "不存在的主题");

        assertThat(response.message()).isEqualTo(KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE);
        assertThat(response.hits()).isEmpty();
    }
}
