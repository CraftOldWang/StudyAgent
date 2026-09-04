package com.studyagent.rag.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.studyagent.config.ElasticsearchProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ElasticsearchIndexerTest {

    @Test
    void indexesSingleDocumentThroughWriteAlias() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        IndexResponse response = mock(IndexResponse.class);
        when(response.id()).thenReturn("chunk-1");
        when(client.index(any(IndexRequest.class))).thenReturn(response);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, properties(2));

        String id = indexer.index(document("chunk-1"));

        assertThat(id).isEqualTo("chunk-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<IndexRequest<ElasticsearchChunkDocument>> requestCaptor =
                ArgumentCaptor.forClass(IndexRequest.class);
        verify(client).index(requestCaptor.capture());
        assertThat(requestCaptor.getValue().index()).isEqualTo("chunks-v1-write");
        assertThat(requestCaptor.getValue().id()).isEqualTo("chunk-1");
        assertThat(requestCaptor.getValue().document().chunkId()).isEqualTo("chunk-1");
        assertThat(requestCaptor.getValue().document().userId()).isEqualTo("user-1");
    }

    @Test
    void bulkIndexesDocumentsThroughWriteAlias() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(false);
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, properties(2));

        indexer.bulkIndex(List.of(document("chunk-1"), document("chunk-2")));

        ArgumentCaptor<BulkRequest> requestCaptor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(requestCaptor.capture());
        assertThat(requestCaptor.getValue().operations()).hasSize(2);
        assertThat(requestCaptor.getValue().operations())
                .allSatisfy(operation -> {
                    assertThat(operation.index().index()).isEqualTo("chunks-v1-write");
                    assertThat(((ElasticsearchChunkDocument) operation.index().document()).userId())
                            .isEqualTo("user-1");
                });
    }

    @Test
    void rejectsDocumentWithoutServerProvidedUserId() {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, properties(2));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> indexer.index(document(null, "chunk-1")))
                .hasMessageContaining("缺少服务端 userId");

        verifyNoInteractions(client);
    }

    private ElasticsearchChunkDocument document(String chunkId) {
        return document("user-1", chunkId);
    }

    private ElasticsearchChunkDocument document(String userId, String chunkId) {
        return new ElasticsearchChunkDocument(
                userId,
                "kb-1",
                "document-1",
                chunkId,
                "parent-1",
                "CHILD",
                0,
                "content",
                "content-hash",
                "demo.pdf",
                "{\"startOffset\":0}",
                new float[]{0.1f, 0.2f},
                "structured-v1",
                "text-embedding-v3",
                LocalDateTime.of(2026, 9, 3, 10, 0));
    }

    private ElasticsearchProperties properties(int dimensions) {
        return new ElasticsearchProperties(
                "http://localhost:9200",
                "chunks-v1",
                "chunks-v1-read",
                "chunks-v1-write",
                dimensions);
    }
}
