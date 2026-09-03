package com.studyagent.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.common.exception.BusinessException;
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

/**
 * Elasticsearch legacy 检索适配器，保留待 Phase 1.4-1.8 迁移的读与删除能力。
 *
 * <p>索引创建和文档写入已迁移到 rag/index 下的官方 Java API Client 实现。</p>
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchChunkIndexer {

    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 执行向量检索，过滤条件包含用户和知识库范围。
     */
    public List<SearchHitChunk> vectorSearch(Long userId, List<Long> knowledgeBaseIds, float[] queryVector, int topK) {
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
        // ES KNN filter 直接限制资源范围，避免召回越权 chunk 后再过滤。
        ObjectNode filter = knn.putObject("filter").putObject("bool");
        ArrayNode must = filter.putArray("must");
        must.add(termQuery("user_id", userId));
        must.add(childChunkFilter());
        ObjectNode terms = objectMapper.createObjectNode();
        ArrayNode ids = terms.putObject("terms").putArray("knowledge_base_id");
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            ids.add(knowledgeBaseId);
        }
        must.add(terms);

        JsonNode response = request("POST", "/" + properties.readAlias() + "/_search", root.toString());
        return readHits(response);
    }

    /**
     * 执行 BM25 关键词检索，过滤条件包含用户和知识库范围。
     */
    public List<SearchHitChunk> bm25Search(Long userId, List<Long> knowledgeBaseIds, String query, int topK) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", topK);
        root.put("_source", true);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode must = bool.putArray("must");
        ObjectNode match = objectMapper.createObjectNode();
        match.putObject("match").putObject("content").put("query", query);
        must.add(match);
        addScopeFilter(bool.putArray("filter"), userId, knowledgeBaseIds);

        JsonNode response = request("POST", "/" + properties.readAlias() + "/_search", root.toString());
        return readHits(response);
    }

    /**
     * 根据子 chunkId 批量补取 ES 内容，并保留用户范围过滤。
     */
    public List<SearchHitChunk> searchByChunkIds(Long userId, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", chunkIds.size());
        root.put("_source", true);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.add(termQuery("user_id", userId));
        filter.add(childChunkFilter());
        ObjectNode terms = objectMapper.createObjectNode();
        ArrayNode ids = terms.putObject("terms").putArray("chunk_id");
        for (String chunkId : chunkIds) {
            ids.add(chunkId);
        }
        filter.add(terms);

        JsonNode response = request("POST", "/" + properties.readAlias() + "/_search", root.toString());
        return readHits(response);
    }

    /**
     * 根据子文档 parent_chunk_id 批量读取父文档。
     *
     * <p>这是父子检索的第二跳：第一跳只召回 CHILD，第二跳用 CHILD.parent_chunk_id 到 ES 取 PARENT。
     * 用户和知识库过滤仍然在 ES 查询里完成，避免把越权父块带入模型上下文。</p>
     */
    public List<SearchHitChunk> searchParentChunks(Long userId, List<Long> knowledgeBaseIds, List<String> parentChunkIds) {
        if (parentChunkIds == null || parentChunkIds.isEmpty()) {
            return List.of();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", parentChunkIds.size());
        root.put("_source", true);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.add(termQuery("user_id", userId));
        filter.add(termQuery("chunk_type", "PARENT"));

        ObjectNode knowledgeBaseTerms = objectMapper.createObjectNode();
        ArrayNode knowledgeBaseIdArray = knowledgeBaseTerms.putObject("terms").putArray("knowledge_base_id");
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            knowledgeBaseIdArray.add(knowledgeBaseId);
        }
        filter.add(knowledgeBaseTerms);

        ObjectNode chunkIdTerms = objectMapper.createObjectNode();
        ArrayNode chunkIdArray = chunkIdTerms.putObject("terms").putArray("chunk_id");
        for (String parentChunkId : parentChunkIds) {
            chunkIdArray.add(parentChunkId);
        }
        filter.add(chunkIdTerms);

        JsonNode response = request("POST", "/" + properties.readAlias() + "/_search", root.toString());
        return readHits(response);
    }

    /**
     * 添加用户和知识库范围过滤。
     */
    private void addScopeFilter(ArrayNode filter, Long userId, List<Long> knowledgeBaseIds) {
        filter.add(termQuery("user_id", userId));
        filter.add(childChunkFilter());
        ObjectNode terms = objectMapper.createObjectNode();
        ArrayNode ids = terms.putObject("terms").putArray("knowledge_base_id");
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            ids.add(knowledgeBaseId);
        }
        filter.add(terms);
    }

    /**
     * 构造 term 查询节点。
     */
    private ObjectNode termQuery(String field, Long value) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("term").put(field, value);
        return root;
    }

    /**
     * 构造字符串 term 查询节点，当前用于 chunk_type 精确过滤。
     */
    private ObjectNode termQuery(String field, String value) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("term").put(field, value);
        return root;
    }

    /**
     * 只召回子 chunk，同时兼容升级前已存在的 ES 文档。
     *
     * <p>新索引文档中 PARENT 和 CHILD 都会进入 ES，但召回阶段只使用 CHILD。旧索引文档没有 chunk_type，如果直接 term 过滤会导致历史资料
     * 全部不可检索。这里允许 missing chunk_type，旧数据会按子块兼容处理，重新处理文档后就会进入标准父子结构。</p>
     */
    private ObjectNode childChunkFilter() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode should = bool.putArray("should");
        should.add(termQuery("chunk_type", "CHILD"));
        ObjectNode missingChunkType = objectMapper.createObjectNode();
        ArrayNode mustNot = missingChunkType.putObject("bool").putArray("must_not");
        mustNot.add(objectMapper.createObjectNode().putObject("exists").put("field", "chunk_type"));
        should.add(missingChunkType);
        bool.put("minimum_should_match", 1);
        return root;
    }

    /**
     * 将 ES hits 转换成基础设施层搜索命中对象。
     */
    private List<SearchHitChunk> readHits(JsonNode response) {
        List<SearchHitChunk> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new SearchHitChunk(
                    source.path("chunk_id").asText(null),
                    source.path("document_id").asLong(),
                    source.path("knowledge_base_id").asLong(),
                    source.path("user_id").asLong(),
                    source.path("parent_chunk_id").isMissingNode() || source.path("parent_chunk_id").isNull()
                            ? null
                            : source.path("parent_chunk_id").asText(null),
                    source.path("chunk_type").asText("CHILD"),
                    source.path("chunk_index").asInt(),
                    source.path("document_title").asText(null),
                    source.path("content").asText(),
                    source.path("metadata_json").asText("{}"),
                    hit.path("_score").asDouble()
            ));
        }
        return hits;
    }

    /**
     * 统一发送 ES HTTP 请求，非 2xx 响应直接转换为业务异常。
     */
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

    /**
     * 拼接 ES endpoint 和请求路径。
     */
    private URI uri(String path) {
        return URI.create(properties.endpoint() + path);
    }
}
