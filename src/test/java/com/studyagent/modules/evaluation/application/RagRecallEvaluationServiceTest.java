package com.studyagent.modules.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.studyagent.config.RagProperties;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.SearchHitChunk;
import com.studyagent.modules.evaluation.domain.RagRetrievalStrategy;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RagRecallEvaluationServiceTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ElasticsearchChunkIndexer elasticsearchChunkIndexer;

    @Test
    void evaluateShouldReportSeedRecallAndParentContextRecallSeparately() {
        RagRecallEvaluationService service = new RagRecallEvaluationService(
                embeddingService,
                elasticsearchChunkIndexer,
                new RagProperties(6, 900, 120, 2400, 240, 10, 10, 60)
        );
        when(embeddingService.embedQuery("RRF 是什么？")).thenReturn(new float[]{0.1f, 0.2f});
        when(elasticsearchChunkIndexer.bm25Search(30L, List.of(20L), "RRF 是什么？", 10))
                .thenReturn(List.of(childHit(101L, 1001L, 1.5d)));
        when(elasticsearchChunkIndexer.vectorSearch(30L, List.of(20L), new float[]{0.1f, 0.2f}, 10))
                .thenReturn(List.of(childHit(102L, 1001L, 0.9d)));
        when(elasticsearchChunkIndexer.searchByChunkIds(30L, List.of(101L, 102L)))
                .thenReturn(List.of(childHit(101L, 1001L, 1.5d), childHit(102L, 1001L, 0.9d)));
        when(elasticsearchChunkIndexer.searchParentChunks(
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(List.of(20L)),
                org.mockito.ArgumentMatchers.eq(List.of(1001L))
        ))
                .thenReturn(List.of(parentHit(1001L)));

        RagRecallEvaluationReport report = service.evaluate(new RagRecallEvaluationRequest(
                30L,
                List.of(20L),
                List.of(1, 2),
                List.of(RagRetrievalStrategy.HYBRID_RRF_PARENT),
                List.of(new RagEvalCase("RRF 是什么？", List.of(101L, 102L), "RRF 是融合排序策略。", null)),
                true
        ));

        RagRecallEvaluationReport.StrategyReport strategyReport = report.strategies().getFirst();
        assertThat(strategyReport.seedRecallAtK()).containsEntry(1, 0.5d).containsEntry(2, 1.0d);
        assertThat(strategyReport.contextRecallAtK()).containsEntry(1, 1.0d).containsEntry(2, 1.0d);
        assertThat(strategyReport.cases().getFirst().expectedContextChunkIds()).containsExactly(1001L);
        assertThat(strategyReport.cases().getFirst().retrievedContextChunkIds()).containsExactly(1001L);
    }

    private SearchHitChunk childHit(Long chunkId, Long parentChunkId, double score) {
        return new SearchHitChunk(
                chunkId,
                10L,
                20L,
                30L,
                parentChunkId,
                DocumentChunk.TYPE_CHILD,
                chunkId.intValue(),
                "demo",
                "content",
                "{}",
                score
        );
    }

    private SearchHitChunk parentHit(Long chunkId) {
        return new SearchHitChunk(
                chunkId,
                10L,
                20L,
                30L,
                null,
                DocumentChunk.TYPE_PARENT,
                0,
                "demo",
                "parent",
                "{}",
                1.0d
        );
    }
}
