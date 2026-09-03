package com.studyagent.modules.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.SearchHitChunk;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.modules.evaluation.domain.RagRetrievalStrategy;
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
    @Mock
    private DocumentChunkMapper documentChunkMapper;
    @Mock
    private DocumentMapper documentMapper;

    @Test
    void evaluateShouldReportSeedRecallAndParentContextRecallSeparately() {
        RagRecallEvaluationService service = new RagRecallEvaluationService(
                embeddingService,
                elasticsearchChunkIndexer,
                new RagProperties(6, 900, 120, 2400, 240, 10, 10, 60),
                documentChunkMapper,
                documentMapper
        );
        when(documentChunkMapper.selectList(any())).thenReturn(
                List.of(documentChunk("chunk-101"), documentChunk("chunk-102"))
        );
        when(documentMapper.selectList(any())).thenReturn(List.of(document(10L, 30L, 20L)));
        when(embeddingService.embedQuery("RRF 是什么？")).thenReturn(new float[]{0.1f, 0.2f});
        when(elasticsearchChunkIndexer.bm25Search(30L, List.of(20L), "RRF 是什么？", 10))
                .thenReturn(List.of(childHit("chunk-101", "parent-1001", 1.5d)));
        when(elasticsearchChunkIndexer.vectorSearch(30L, List.of(20L), new float[]{0.1f, 0.2f}, 10))
                .thenReturn(List.of(childHit("chunk-102", "parent-1001", 0.9d)));
        when(elasticsearchChunkIndexer.searchByChunkIds(30L, List.of("chunk-101", "chunk-102")))
                .thenReturn(List.of(childHit("chunk-101", "parent-1001", 1.5d), childHit("chunk-102", "parent-1001", 0.9d)));
        when(elasticsearchChunkIndexer.searchParentChunks(
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(List.of(20L)),
                org.mockito.ArgumentMatchers.eq(List.of("parent-1001"))
        ))
                .thenReturn(List.of(parentHit("parent-1001")));

        RagRecallEvaluationReport report = service.evaluate(new RagRecallEvaluationRequest(
                30L,
                List.of(20L),
                List.of(1, 2),
                List.of(RagRetrievalStrategy.HYBRID_RRF_PARENT),
                List.of(new RagEvalCase("RRF 是什么？", List.of("chunk-101", "chunk-102"), "RRF 是融合排序策略。", null)),
                true
        ));

        RagRecallEvaluationReport.StrategyReport strategyReport = report.strategies().getFirst();
        assertThat(strategyReport.seedRecallAtK()).containsEntry(1, 0.5d).containsEntry(2, 1.0d);
        assertThat(strategyReport.contextRecallAtK()).containsEntry(1, 1.0d).containsEntry(2, 1.0d);
        assertThat(strategyReport.cases().getFirst().expectedContextChunkIds()).containsExactly("parent-1001");
        assertThat(strategyReport.cases().getFirst().retrievedContextChunkIds()).containsExactly("parent-1001");
    }

    @Test
    void evaluateShouldFailWhenExpectedChunkDoesNotExist() {
        RagRecallEvaluationService service = service();
        when(documentChunkMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.evaluate(requestWithExpectedChunk("missing")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评测真值 chunk 不存在");
    }

    @Test
    void evaluateShouldFailWhenExpectedChunkDocumentIsOutsideScope() {
        RagRecallEvaluationService service = service();
        when(documentChunkMapper.selectList(any())).thenReturn(List.of(documentChunk("chunk-101")));
        when(documentMapper.selectList(any())).thenReturn(List.of(document(10L, 31L, 20L)));

        assertThatThrownBy(() -> service.evaluate(requestWithExpectedChunk("chunk-101")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评测真值 chunk 超出 user/knowledgeBase scope");
    }

    private RagRecallEvaluationService service() {
        return new RagRecallEvaluationService(
                embeddingService,
                elasticsearchChunkIndexer,
                new RagProperties(6, 900, 120, 2400, 240, 10, 10, 60),
                documentChunkMapper,
                documentMapper
        );
    }

    private RagRecallEvaluationRequest requestWithExpectedChunk(String chunkId) {
        return new RagRecallEvaluationRequest(
                30L,
                List.of(20L),
                List.of(1),
                List.of(RagRetrievalStrategy.BM25_ONLY),
                List.of(new RagEvalCase("问题", List.of(chunkId), null, null)),
                false
        );
    }

    private DocumentChunk documentChunk(String chunkId) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(chunkId);
        chunk.setDocumentId(10L);
        return chunk;
    }

    private Document document(Long id, Long userId, Long knowledgeBaseId) {
        Document document = new Document();
        document.setId(id);
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        return document;
    }

    private SearchHitChunk childHit(String chunkId, String parentChunkId, double score) {
        return new SearchHitChunk(
                chunkId,
                10L,
                20L,
                30L,
                parentChunkId,
                "CHILD",
                1,
                "demo",
                "content",
                "{}",
                score
        );
    }

    private SearchHitChunk parentHit(String chunkId) {
        return new SearchHitChunk(
                chunkId,
                10L,
                20L,
                30L,
                null,
                "PARENT",
                0,
                "demo",
                "parent",
                "{}",
                1.0d
        );
    }
}
