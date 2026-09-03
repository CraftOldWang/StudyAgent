package com.studyagent.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.ElasticsearchProperties;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 使用官方 Java API Client 写入 chunks-v1-write。
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchIndexer {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public String index(ElasticsearchChunkDocument document) {
        validateDimensions(document);
        IndexRequest<ElasticsearchChunkDocument> request = IndexRequest.of(i -> i
                .index(properties.writeAlias())
                .id(document.chunkId())
                .document(document));
        try {
            IndexResponse response = client.index(request);
            return response.id();
        } catch (IOException ex) {
            throw new BusinessException("写入 Elasticsearch chunk 失败: chunkId="
                    + document.chunkId() + ", error=" + ex.getMessage());
        }
    }

    public void bulkIndex(List<ElasticsearchChunkDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        BulkRequest.Builder request = new BulkRequest.Builder();
        for (ElasticsearchChunkDocument document : documents) {
            validateDimensions(document);
            request.operations(operation -> operation.index(index -> index
                    .index(properties.writeAlias())
                    .id(document.chunkId())
                    .document(document)));
        }
        try {
            BulkResponse response = client.bulk(request.build());
            if (response.errors()) {
                String failures = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> item.id() + ": " + item.error().reason())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("unknown bulk error");
                throw new BusinessException("批量写入 Elasticsearch chunk 失败: " + failures);
            }
        } catch (IOException ex) {
            throw new BusinessException("批量写入 Elasticsearch chunk 请求失败: " + ex.getMessage());
        }
    }

    private void validateDimensions(ElasticsearchChunkDocument document) {
        if (document.userId() == null || document.userId().isBlank()) {
            throw new BusinessException("Elasticsearch chunk 缺少服务端 userId: chunkId=" + document.chunkId());
        }
        if (document.embedding().length != properties.vectorDimensions()) {
            throw new BusinessException("Embedding 维度与 Elasticsearch 配置不一致: actual="
                    + document.embedding().length + ", expected=" + properties.vectorDimensions());
        }
    }
}
