package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ParentAggregatorTest {

    private ElasticsearchClient client;
    private ParentAggregator aggregator;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        aggregator = new ParentAggregator(client, properties());
    }

    @Test
    void loadsParentsInOneScopedQueryAndKeepsEachChildProvenance() throws IOException {
        RetrievalHit.Provenance provenance = new RetrievalHit.Provenance("doc-1", "demo.pdf", "location");
        RetrievalHit found = new RetrievalHit(
                "child-1", "parent-1", "child one", provenance, 0.8, RetrievalStrategy.RRF);
        RetrievalHit parentMissing = new RetrievalHit(
                "child-2",
                "parent-missing",
                "child two",
                provenance,
                0.7,
                RetrievalStrategy.VECTOR
        );
        ElasticsearchChunkDocument parent = mock(ElasticsearchChunkDocument.class);
        when(parent.chunkId()).thenReturn("parent-1");
        when(parent.content()).thenReturn("完整父块内容");
        SearchResponse<ElasticsearchChunkDocument> response = responseWith(Hit.of(hit -> hit
                .index("chunks-v1")
                .id("parent-1")
                .source(parent)));
        when(client.search(any(SearchRequest.class), eq(ElasticsearchChunkDocument.class)))
                .thenReturn(response);

        List<RetrievalHit> result = aggregator.aggregate("user-4", "kb-7", List.of(found, parentMissing));

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(requestCaptor.capture(), eq(ElasticsearchChunkDocument.class));
        SearchRequest request = requestCaptor.getValue();
        assertThat(request.index()).containsExactly("chunks-v1-read");
        assertThat(request.size()).isEqualTo(2);
        assertThat(request.query().bool().filter()).hasSize(4);
        assertThat(request.query().bool().filter().get(0).term().field()).isEqualTo("user_id");
        assertThat(request.query().bool().filter().get(0).term().value().stringValue())
                .isEqualTo("user-4");
        assertThat(request.query().bool().filter().get(1).term().field())
                .isEqualTo("knowledge_base_id");
        assertThat(request.query().bool().filter().get(1).term().value().stringValue())
                .isEqualTo("kb-7");
        assertThat(request.query().bool().filter().get(2).term().value().stringValue())
                .isEqualTo("PARENT");
        assertThat(request.query().bool().filter().get(3).terms().field()).isEqualTo("chunk_id");
        assertThat(request.query().bool().filter().get(3).terms().terms().value())
                .extracting(value -> value.stringValue())
                .containsExactly("parent-1", "parent-missing");
        assertThat(result).containsExactly(
                new RetrievalHit(
                        "child-1", "parent-1", "完整父块内容", provenance, 0.8, RetrievalStrategy.RRF),
                parentMissing
        );
    }

    @Test
    void returnsChildUnchangedWhenItHasNoParentReference() throws IOException {
        RetrievalHit child = new RetrievalHit(
                "child-1", null, "standalone child", null, 1.2, RetrievalStrategy.BM25);

        assertThat(aggregator.aggregate("user-4", "kb-7", List.of(child))).containsExactly(child);
        verify(client, never()).search(any(SearchRequest.class), eq(ElasticsearchChunkDocument.class));
    }

    @SuppressWarnings("unchecked")
    private SearchResponse<ElasticsearchChunkDocument> responseWith(Hit<ElasticsearchChunkDocument> hit) {
        SearchResponse<ElasticsearchChunkDocument> response = mock(SearchResponse.class);
        HitsMetadata<ElasticsearchChunkDocument> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);
        when(hits.hits()).thenReturn(List.of(hit));
        return response;
    }

    private ElasticsearchProperties properties() {
        return new ElasticsearchProperties(
                "http://localhost:9200",
                "chunks-v1",
                "chunks-v1-read",
                "chunks-v1-write",
                3
        );
    }
}
