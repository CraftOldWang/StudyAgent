package com.studyagent.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.common.config.ElasticsearchProperties;
import com.studyagent.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ElasticsearchChunkIndexer {

    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @PostConstruct
    public void init() {
        ensureIndex();
    }

    public String index(IndexedChunk chunk) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chunk_id", chunk.chunkId());
        body.put("document_id", chunk.documentId());
        body.put("knowledge_base_id", chunk.knowledgeBaseId());
        body.put("user_id", chunk.userId());
        body.put("chunk_index", chunk.chunkIndex());
        body.put("content", chunk.content());
        ArrayNode embedding = body.putArray("embedding");
        for (float value : chunk.embedding()) {
            embedding.add(value);
        }

        String esDocId = String.valueOf(chunk.chunkId());
        request("PUT", "/" + properties.chunkIndex() + "/_doc/" + esDocId, body.toString());
        return esDocId;
    }

    public void deleteByDocumentId(Long documentId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("query").putObject("term").put("document_id", documentId);
        request("POST", "/" + properties.chunkIndex() + "/_delete_by_query?refresh=true", root.toString());
    }

    public List<SearchHitChunk> search(Long userId, List<Long> knowledgeBaseIds, float[] queryVector, int topK) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", topK);
        root.put("_source", true);
        ObjectNode knn = root.putObject("knn");
        knn.put("field", "embedding");
        ArrayNode vector = knn.putArray("query_vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        knn.put("k", topK);
        knn.put("num_candidates", Math.max(topK * 5, 30));
        ObjectNode filter = knn.putObject("filter").putObject("bool");
        ArrayNode must = filter.putArray("must");
        must.add(termQuery("user_id", userId));
        ObjectNode terms = objectMapper.createObjectNode();
        ArrayNode ids = terms.putObject("terms").putArray("knowledge_base_id");
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            ids.add(knowledgeBaseId);
        }
        must.add(terms);

        JsonNode response = request("POST", "/" + properties.chunkIndex() + "/_search", root.toString());
        List<SearchHitChunk> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new SearchHitChunk(
                    source.path("chunk_id").asLong(),
                    source.path("document_id").asLong(),
                    source.path("knowledge_base_id").asLong(),
                    source.path("chunk_index").asInt(),
                    source.path("content").asText(),
                    hit.path("_score").asDouble()
            ));
        }
        return hits;
    }

    private void ensureIndex() {
        HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(uri("/" + properties.chunkIndex()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(headRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return;
            }
            if (response.statusCode() != 404) {
                throw new BusinessException("检查 Elasticsearch 索引失败: " + response.statusCode());
            }
        } catch (IOException ex) {
            throw new BusinessException("连接 Elasticsearch 失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("检查 Elasticsearch 索引被中断");
        }

        ObjectNode mapping = objectMapper.createObjectNode();
        ObjectNode propertiesNode = mapping.putObject("mappings").putObject("properties");
        propertiesNode.putObject("chunk_id").put("type", "long");
        propertiesNode.putObject("document_id").put("type", "long");
        propertiesNode.putObject("knowledge_base_id").put("type", "long");
        propertiesNode.putObject("user_id").put("type", "long");
        propertiesNode.putObject("chunk_index").put("type", "integer");
        propertiesNode.putObject("content").put("type", "text").put("analyzer", "standard");
        ObjectNode embedding = propertiesNode.putObject("embedding");
        embedding.put("type", "dense_vector");
        embedding.put("dims", properties.vectorDimensions());
        embedding.put("index", true);
        embedding.put("similarity", "cosine");

        request("PUT", "/" + properties.chunkIndex(), mapping.toString());
    }

    private ObjectNode termQuery(String field, Long value) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("term").put(field, value);
        return root;
    }

    private JsonNode request(String method, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new BusinessException("Elasticsearch 请求失败: " + response.statusCode() + " " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("Elasticsearch 请求异常: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Elasticsearch 请求被中断");
        }
    }

    private URI uri(String path) {
        return URI.create(properties.endpoint() + path);
    }
}
