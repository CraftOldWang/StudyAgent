package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetrievalServiceTest {

    private BM25Retriever bm25Retriever;
    private VectorRetriever vectorRetriever;
    private ParentAggregator parentAggregator;
    private RetrievalService service;

    @BeforeEach
    void setUp() {
        bm25Retriever = mock(BM25Retriever.class);
        vectorRetriever = mock(VectorRetriever.class);
        parentAggregator = mock(ParentAggregator.class);
        service = new RetrievalService(bm25Retriever, vectorRetriever, parentAggregator);
    }

    @Test
    void bm25ModeUsesCandidateLimitAndAppliesTopKInOrchestration() {
        List<RetrievalHit> candidates = List.of(hit("a", RetrievalStrategy.BM25, 3.0),
                hit("b", RetrievalStrategy.BM25, 2.0), hit("c", RetrievalStrategy.BM25, 1.0));
        when(bm25Retriever.retrieve("user-4", "kb-7", "多态", 6)).thenReturn(candidates);

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.BM25, "user-4", "kb-7", "多态", null, 6, 2);

        assertThat(result).containsExactly(candidates.get(0), candidates.get(1));
        verify(vectorRetriever, never()).retrieve("user-4", "kb-7", null, 6);
    }

    @Test
    void vectorModeUsesCandidateLimitAndAppliesTopKInOrchestration() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<RetrievalHit> candidates = List.of(hit("a", RetrievalStrategy.VECTOR, 0.9),
                hit("b", RetrievalStrategy.VECTOR, 0.8));
        when(vectorRetriever.retrieve("user-4", "kb-7", vector, 5)).thenReturn(candidates);

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.VECTOR, "user-4", "kb-7", null, vector, 5, 1);

        assertThat(result).containsExactly(candidates.getFirst());
        verify(bm25Retriever, never()).retrieve("user-4", "kb-7", null, 5);
    }

    @Test
    void rrfModeUsesRankConstantSixtyAndReturnsUnifiedHits() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(bm25Retriever.retrieve("user-4", "kb-7", "多态", 6)).thenReturn(List.of(
                hit("a", RetrievalStrategy.BM25, 7.0),
                hit("b", RetrievalStrategy.BM25, 6.0)
        ));
        when(vectorRetriever.retrieve("user-4", "kb-7", vector, 6)).thenReturn(List.of(
                hit("b", RetrievalStrategy.VECTOR, 0.95),
                hit("c", RetrievalStrategy.VECTOR, 0.90)
        ));

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.RRF, "user-4", "kb-7", "多态", vector, 6, 3);

        assertThat(result).extracting(RetrievalHit::chunkId).containsExactly("b", "a", "c");
        assertThat(result).extracting(RetrievalHit::strategy)
                .containsOnly(RetrievalStrategy.RRF);
        assertThat(result.getFirst().score()).isEqualTo(1.0 / 62.0 + 1.0 / 61.0);
    }

    @Test
    void parentModeAggregatesAllFusedCandidatesBeforeTopK() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(bm25Retriever.retrieve("user-4", "kb-7", "多态", 6))
                .thenReturn(List.of(hit("a", RetrievalStrategy.BM25, 2.0)));
        when(vectorRetriever.retrieve("user-4", "kb-7", vector, 6))
                .thenReturn(List.of(hit("b", RetrievalStrategy.VECTOR, 0.8)));
        List<RetrievalHit> parents = List.of(
                new RetrievalHit("a", "pa", "parent a", 0.1, RetrievalStrategy.RRF),
                new RetrievalHit("b", "pb", "parent b", 0.09, RetrievalStrategy.RRF)
        );
        when(parentAggregator.aggregate(org.mockito.ArgumentMatchers.eq("user-4"),
                org.mockito.ArgumentMatchers.eq("kb-7"), anyList())).thenReturn(parents);

        List<RetrievalHit> result = service.retrieve(
                RetrievalMode.PARENT, "user-4", "kb-7", "多态", vector, 6, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievalHit>> hitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(parentAggregator).aggregate(
                org.mockito.ArgumentMatchers.eq("user-4"),
                org.mockito.ArgumentMatchers.eq("kb-7"),
                hitsCaptor.capture()
        );
        assertThat(hitsCaptor.getValue()).hasSize(2);
        assertThat(result).containsExactly(parents.getFirst());
    }

    private RetrievalHit hit(String chunkId, RetrievalStrategy strategy, double score) {
        return new RetrievalHit(chunkId, "parent-" + chunkId, "content " + chunkId, score, strategy);
    }
}
