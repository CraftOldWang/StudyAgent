package com.studyagent.rag.index;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.studyagent.config.ElasticsearchProperties;
import org.junit.jupiter.api.Test;

class ElasticsearchIndexInitializerTest {

    @Test
    void buildsCompleteChunksV1MappingAndAliases() {
        ElasticsearchIndexInitializer initializer = new ElasticsearchIndexInitializer(null, properties());

        CreateIndexRequest request = initializer.createIndexRequest();

        assertThat(request.index()).isEqualTo("chunks-v1");
        assertThat(request.aliases()).containsKeys("chunks-v1-read", "chunks-v1-write");
        assertThat(request.aliases().get("chunks-v1-write").isWriteIndex()).isTrue();
        assertThat(request.mappings().properties()).containsOnlyKeys(
                "user_id",
                "knowledge_base_id",
                "document_id",
                "document_title",
                "source_location",
                "chunk_id",
                "parent_chunk_id",
                "chunk_type",
                "chunk_index",
                "content",
                "content_hash",
                "embedding",
                "chunker_version",
                "embedding_model",
                "created_at");
        assertThat(request.mappings().properties().get("content").text().analyzer())
                .isEqualTo("standard");
        assertThat(request.mappings().properties().get("content").text().fields())
                .containsKey("keyword");
        assertThat(request.mappings().properties().get("embedding").denseVector().dims())
                .isEqualTo(1024);
        assertThat(request.mappings().properties().get("embedding").denseVector().index())
                .isTrue();
        assertThat(request.mappings().properties().get("embedding").denseVector().similarity())
                .isEqualTo("cosine");
    }

    private ElasticsearchProperties properties() {
        return new ElasticsearchProperties(
                "http://localhost:9200",
                "chunks-v1",
                "chunks-v1-read",
                "chunks-v1-write",
                1024);
    }
}
