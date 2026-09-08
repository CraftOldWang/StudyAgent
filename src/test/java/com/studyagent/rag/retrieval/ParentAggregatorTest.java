package com.studyagent.rag.retrieval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ParentAggregatorTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ParentAggregator aggregator = new ParentAggregator(client,
            new ElasticsearchProperties("http://localhost:9200", "chunks-v1", "chunks-v1-read", "chunks-v1-write", 3));

    @Test
    void deduplicatesSharedParentAndUsesItsOwnSourceCoordinatesWithinScope() throws Exception {
        var childSource = new RetrievalHit.Provenance("doc", "title", "child-location");
        var one = new RetrievalHit("c1", "p1", "first child", childSource, 0.8, RetrievalStrategy.RRF);
        var two = new RetrievalHit("c2", "p1", "second child", childSource, 0.7, RetrievalStrategy.RRF);
        var parent = mock(ElasticsearchChunkDocument.class);
        when(parent.chunkId()).thenReturn("p1");
        when(parent.content()).thenReturn("complete parent");
        when(parent.documentId()).thenReturn("doc");
        when(parent.documentTitle()).thenReturn("title");
        when(parent.sourceLocation()).thenReturn("parent-location");
        stub(List.of(Hit.of(hit -> hit.index("chunks-v1").id("p1").source(parent))));
        var contexts = aggregator.aggregate("user-4", "kb-7", List.of(one, two));
        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.chunkId()).isEqualTo("p1");
            assertThat(context.content()).isEqualTo("complete parent");
            assertThat(context.provenance().sourceLocation()).isEqualTo("parent-location");
            assertThat(context.score()).isEqualTo(0.8);
        });
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(request.capture(), eq(ElasticsearchChunkDocument.class));
        assertThat(request.getValue().size()).isEqualTo(1);
        assertThat(request.getValue().query().bool().filter()).hasSize(4);
        assertThat(request.getValue().query().bool().filter().get(0).term().value().stringValue()).isEqualTo("user-4");
        assertThat(request.getValue().query().bool().filter().get(1).term().value().stringValue()).isEqualTo("kb-7");
    }

    @Test
    void missingReferencedParentIsAnExplicitIndexError() throws Exception {
        stub(List.of());
        var child = new RetrievalHit("child", "missing", "text", null, 1, RetrievalStrategy.RRF);
        assertThatThrownBy(() -> aggregator.aggregate("u", "kb", List.of(child))).hasMessageContaining("parent 缺失");
    }

    @Test
    void standaloneChildDoesNotQueryParents() {
        var child = new RetrievalHit("child", null, "text", null, 1, RetrievalStrategy.BM25);
        assertThat(aggregator.aggregate("u", "kb", List.of(child))).singleElement()
                .satisfies(context -> assertThat(context.chunkId()).isEqualTo("child"));
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    private void stub(List<Hit<ElasticsearchChunkDocument>> hits) throws Exception {
        SearchResponse<ElasticsearchChunkDocument> response = mock(SearchResponse.class);
        HitsMetadata<ElasticsearchChunkDocument> metadata = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(metadata);
        when(metadata.hits()).thenReturn(hits);
        when(client.search(any(SearchRequest.class), eq(ElasticsearchChunkDocument.class))).thenReturn(response);
    }
}
