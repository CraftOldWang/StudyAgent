# D-Q01：Elasticsearch 访问方式

> 状态：READY_FOR_DECISION  
> 与 D-Q08 联合设计

## 选项

### A. 继续使用 JDK HttpClient + REST JSON

优点：依赖少、DSL 完全透明、能立即使用任何 ES API。

缺点：mapping、bulk、错误响应、序列化、字段变更、异步与版本兼容都要手写；当前 412 行 indexer 会继续膨胀。

适用：短期 POC，不适合稳定 ingest/rag 实现。

### B. Elasticsearch Java API Client

Elastic 官方强类型客户端，提供 fluent DSL、同步/异步 API 与 Bulk Ingester。

优点：

- 能完整表达 BM25、kNN、RRF、mapping、alias、bulk 与 explain；
- 类型安全，减少裸 JSON 解析；
- Bulk Ingester 可配置操作数、字节数、flush interval 和并发请求。

缺点：仍然需要理解 ES DSL；客户端与服务器版本要受控；builder 类型较重。

适用：StudyAgent 需要保留复杂检索控制时最合适。

### C. Spring Data Elasticsearch

优点：Repository、对象映射与 `ElasticsearchOperations` 对 Spring 项目友好。

缺点：复杂 hybrid、RRF、parent 回查和 mapping 版本仍会落到 native query；再增加一层对象映射未必减少复杂度。

适用：CRUD/简单搜索，不是当前 RAG 策略唯一入口的首选。

### D. Spring AI ElasticsearchVectorStore

优点：统一 `VectorStore`、EmbeddingModel、topK、similarity threshold 与 metadata filter。

缺点：主要抽象是 `Document + embedding similarity`；BM25 + vector + 应用 RRF + parent-child 不属于它的完整策略模型。高级 mapping 仍要手工管理。

适用：标准向量相似度 adapter；不适合作为 StudyAgent 检索核心的唯一接口。

## 推荐

推荐 B：

- `integration/search` 使用官方 Java API Client；
- `rag/retrieval` 保留自有 Retriever/Indexer 契约；
- Java Client 不越过 adapter 进入核心策略；
- 不把 Spring AI VectorStore 当 RAG 策略层，只在未来需要通用 vector adapter 时评估。

索引维度、similarity、analyzer、chunker/embedding version 与 alias 切换仍由 StudyAgent 管理，不能交给自动 schema 创建。

## 需要用户拍板

- A/B/C/D；
- 是否允许同时使用 B 和 D，还是保持单一 ES adapter；
- bulk indexing 首版是否采用 Bulk Ingester；
- ES client/server 的版本兼容策略。

## 一手来源

- [Elastic Java API Client](https://www.elastic.co/docs/reference/elasticsearch/clients/java)
- [Elastic Bulk Ingester](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/indexing-bulk.html)
- [Spring AI Elasticsearch VectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/elasticsearch.html)
