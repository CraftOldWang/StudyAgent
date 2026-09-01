package com.studyagent.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studyagent.config.ElasticsearchProperties;
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

/**
 * Elasticsearch chunk 索引适配器，封装索引创建、写入、删除和检索请求。
 *
 * <p>业务层只关心 IndexedChunk 和 SearchHitChunk，不直接依赖 ES SDK 或 HTTP 细节。</p>
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
     * 应用启动时确保 chunk 索引存在且向量维度与配置一致。
     */
    @PostConstruct
    public void init() {
        ensureIndex();
    }

    /**
     * 写入单个 chunk 到 ES，并返回业务可追踪的 es_doc_id。
     */
    public String index(IndexedChunk chunk) {
        if (chunk.embedding().length != properties.vectorDimensions()) {
            throw new BusinessException("Embedding 维度与 Elasticsearch 配置不一致: actual="
                    + chunk.embedding().length + ", expected=" + properties.vectorDimensions());
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chunk_id", chunk.chunkId());
        body.put("document_id", chunk.documentId());
        body.put("knowledge_base_id", chunk.knowledgeBaseId());
        body.put("user_id", chunk.userId());
        putNullableLong(body, "parent_chunk_id", chunk.parentChunkId());
        body.put("chunk_type", chunk.chunkType());
        body.put("chunk_index", chunk.chunkIndex());
        body.put("document_title", chunk.documentTitle());
        body.put("content", chunk.content());
        body.put("metadata_json", chunk.metadataJson());
        ArrayNode embedding = body.putArray("embedding");
        for (float value : chunk.embedding()) {
            embedding.add(value);
        }

        // 使用 chunkId 作为 ES 文档 ID，便于 MySQL document_chunks.es_doc_id 反查和幂等覆盖。
        String esDocId = String.valueOf(chunk.chunkId());
        request("PUT", "/" + properties.chunkIndex() + "/_doc/" + esDocId, body.toString());
        return esDocId;
    }

    /**
     * 删除指定文档下的全部 ES chunk，用于文档重新处理前清理旧索引。
     */
    public void deleteByDocumentId(Long documentId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("query").putObject("term").put("document_id", documentId);
        request("POST", "/" + properties.chunkIndex() + "/_delete_by_query?refresh=true", root.toString());
    }

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

        JsonNode response = request("POST", "/" + properties.chunkIndex() + "/_search", root.toString());
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

        JsonNode response = request("POST", "/" + properties.chunkIndex() + "/_search", root.toString());
        return readHits(response);
    }

    /**
     * 根据子 chunkId 批量补取 ES 内容，并保留用户范围过滤。
     */
    public List<SearchHitChunk> searchByChunkIds(Long userId, List<Long> chunkIds) {
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
        for (Long chunkId : chunkIds) {
            ids.add(chunkId);
        }
        filter.add(terms);

        JsonNode response = request("POST", "/" + properties.chunkIndex() + "/_search", root.toString());
        return readHits(response);
    }

    /**
     * 根据子文档 parent_chunk_id 批量读取父文档。
     *
     * <p>这是父子检索的第二跳：第一跳只召回 CHILD，第二跳用 CHILD.parent_chunk_id 到 ES 取 PARENT。
     * 用户和知识库过滤仍然在 ES 查询里完成，避免把越权父块带入模型上下文。</p>
     */
    public List<SearchHitChunk> searchParentChunks(Long userId, List<Long> knowledgeBaseIds, List<Long> parentChunkIds) {
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
        for (Long parentChunkId : parentChunkIds) {
            chunkIdArray.add(parentChunkId);
        }
        filter.add(chunkIdTerms);

        JsonNode response = request("POST", "/" + properties.chunkIndex() + "/_search", root.toString());
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
     * 创建 ES chunk 索引或校验已有索引向量维度。
     */
    private void ensureIndex() {
        HttpRequest headRequest = HttpRequest.newBuilder()
                .uri(uri("/" + properties.chunkIndex()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(headRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                validateExistingIndexDimensions();
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

        // 显式声明 dense_vector 维度，应用配置变化时必须重建索引或改用新索引名。
        ObjectNode mapping = objectMapper.createObjectNode();
        ObjectNode propertiesNode = mapping.putObject("mappings").putObject("properties");
        propertiesNode.putObject("chunk_id").put("type", "long");
        propertiesNode.putObject("document_id").put("type", "long");
        propertiesNode.putObject("knowledge_base_id").put("type", "long");
        propertiesNode.putObject("user_id").put("type", "long");
        propertiesNode.putObject("parent_chunk_id").put("type", "long");
        propertiesNode.putObject("chunk_type").put("type", "keyword");
        propertiesNode.putObject("chunk_index").put("type", "integer");
        propertiesNode.putObject("document_title").put("type", "keyword");
        propertiesNode.putObject("content").put("type", "text").put("analyzer", "standard");
        propertiesNode.putObject("metadata_json").put("type", "keyword").put("index", false);
        ObjectNode embedding = propertiesNode.putObject("embedding");
        embedding.put("type", "dense_vector");
        embedding.put("dims", properties.vectorDimensions());
        embedding.put("index", true);
        embedding.put("similarity", "cosine");

        request("PUT", "/" + properties.chunkIndex(), mapping.toString());
    }

    /**
     * 校验已有索引 embedding 维度，避免查询和写入时出现隐蔽错误。
     */
    private void validateExistingIndexDimensions() {
        JsonNode mapping = request("GET", "/" + properties.chunkIndex() + "/_mapping", null);
        JsonNode embedding = mapping.path(properties.chunkIndex())
                .path("mappings")
                .path("properties")
                .path("embedding");
        if (embedding.isMissingNode()) {
            throw new BusinessException("Elasticsearch 索引缺少 embedding 字段: " + properties.chunkIndex());
        }
        int actualDimensions = embedding.path("dims").asInt(-1);
        if (actualDimensions != properties.vectorDimensions()) {
            throw new BusinessException("Elasticsearch 索引向量维度与应用配置不一致: index="
                    + properties.chunkIndex()
                    + ", actual=" + actualDimensions
                    + ", expected=" + properties.vectorDimensions()
                    + "。请删除旧索引或改用新的 study-agent.elasticsearch.chunk-index 后重启应用。");
        }
        ensureExistingChunkTypeMapping(mapping);
    }

    /**
     * 为已有索引补齐 chunk_type 映射。
     *
     * <p>父子检索要求 ES 只召回 CHILD 文档。chunk_type 必须是 keyword，不能依赖动态 text 映射，
     * 否则 term 过滤会因为分词和大小写处理导致召回为空或不稳定。</p>
     */
    private void ensureExistingChunkTypeMapping(JsonNode mapping) {
        JsonNode chunkType = mapping.path(properties.chunkIndex())
                .path("mappings")
                .path("properties")
                .path("chunk_type");
        if (chunkType.isMissingNode()) {
            ObjectNode update = objectMapper.createObjectNode();
            update.putObject("properties").putObject("chunk_type").put("type", "keyword");
            request("PUT", "/" + properties.chunkIndex() + "/_mapping", update.toString());
            return;
        }
        String type = chunkType.path("type").asText("");
        if (!"keyword".equals(type)) {
            throw new BusinessException("Elasticsearch 索引 chunk_type 字段必须是 keyword: index="
                    + properties.chunkIndex()
                    + ", actual=" + type
                    + "。请删除旧索引或改用新的 study-agent.elasticsearch.chunk-index 后重启应用。");
        }
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
                    source.path("chunk_id").asLong(),
                    source.path("document_id").asLong(),
                    source.path("knowledge_base_id").asLong(),
                    source.path("user_id").asLong(),
                    source.path("parent_chunk_id").isMissingNode() || source.path("parent_chunk_id").isNull()
                            ? null
                            : source.path("parent_chunk_id").asLong(),
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
     * 写入可空 Long 字段。
     */
    private void putNullableLong(ObjectNode body, String field, Long value) {
        if (value == null) {
            body.putNull(field);
            return;
        }
        body.put(field, value);
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
