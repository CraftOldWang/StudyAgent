package com.studyagent.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 创建 chunks-v1 物理索引及其读写别名。
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchIndexInitializer {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    @PostConstruct
    public void initialize() {
        try {
            if (client.indices().exists(e -> e.index(properties.physicalIndex())).value()) {
                ensureAlias(properties.readAlias(), false);
                ensureAlias(properties.writeAlias(), true);
                return;
            }
            client.indices().create(createIndexRequest());
        } catch (IOException ex) {
            throw new BusinessException("初始化 Elasticsearch chunks-v1 索引失败: " + ex.getMessage());
        }
    }

    CreateIndexRequest createIndexRequest() {
        return CreateIndexRequest.of(c -> c
                    .index(properties.physicalIndex())
                    .aliases(properties.readAlias(), a -> a)
                    .aliases(properties.writeAlias(), a -> a.isWriteIndex(true))
                    .mappings(m -> m
                            .properties("user_id", p -> p.keyword(k -> k))
                            .properties("knowledge_base_id", p -> p.keyword(k -> k))
                            .properties("document_id", p -> p.keyword(k -> k))
                            .properties("chunk_id", p -> p.keyword(k -> k))
                            .properties("parent_chunk_id", p -> p.keyword(k -> k))
                            .properties("chunk_type", p -> p.keyword(k -> k))
                            .properties("chunk_index", p -> p.integer(i -> i))
                            .properties("content", p -> p.text(t -> t
                                    .analyzer("standard")
                                    .fields("keyword", f -> f.keyword(k -> k))))
                            .properties("content_hash", p -> p.keyword(k -> k))
                            .properties("embedding", p -> p.denseVector(d -> d
                                    .dims(properties.vectorDimensions())
                                    .index(true)
                                    .similarity("cosine")))
                            .properties("chunker_version", p -> p.keyword(k -> k))
                            .properties("embedding_model", p -> p.keyword(k -> k))
                            .properties("created_at", p -> p.date(d -> d))));
    }

    private void ensureAlias(String alias, boolean writeIndex) throws IOException {
        if (!client.indices().existsAlias(e -> e.name(alias)).value()) {
            client.indices().putAlias(a -> a
                    .index(properties.physicalIndex())
                    .name(alias)
                    .isWriteIndex(writeIndex));
        }
    }
}
