# D-Q08：分块与检索策略

> 状态：READY_FOR_DECISION  
> 与 D-Q01、D-Q12 联合设计

## 分块选项

### A. 固定字符窗口

实现稳定，但字符数不等于 token，会切断句子、代码、表格和定义。只适合作为最低 baseline。

### B. Token Window + Overlap

比字符窗口更接近模型预算，参数可重复。仍可能破坏结构。

### C. 结构/格式感知 + 递归 fallback

先按标题、章节、段落、列表、代码、表格、页码切；超长块再按 paragraph → sentence → token 递归拆分。

优点：引用清晰，适合教材和技术资料。缺点：解析质量直接影响结果；不同格式需要不同 parser。

### D. Semantic Chunking

使用句子 embedding 相似度决定 breakpoint。

优点：边界可能更语义化。缺点：额外成本、阈值不稳定、可解释性与版本复现更差；论文结果并不支持无条件优于简单切分。

推荐 C 为主，B 为 fallback/baseline；D 只作为评测实验。

## Parent-child

推荐保留应用层 parent-child：child 召回，`parent_chunk_id` 二次获取上下文。不要改为 ES `join`，因为官方 join 需要 routing，并引入 global ordinals、内存和查询开销。

## 中文 Analyzer

| 方案 | 特点 | 代价 |
| --- | --- | --- |
| standard | 无插件、Unicode baseline | 中文不是词级分析 |
| SmartCN | Elastic 官方插件，简体中文概率分词 | 每个节点安装并重启；配置能力有限 |
| IK | 社区插件、自定义词典生态较多 | 非 Elastic core；ES 版本、词典和集群升级风险更高 |

不要直接凭名称选择。用 `_analyze` 对 Java/AI 术语、中英混合、数字、缩写建立词法 Golden Cases，再用 retrieval Golden Cases 比较。

## 检索 Pipeline 选项

推荐保持可替换的阶段：

```text
permission/KB filter
-> query normalization
-> optional rewrite
-> BM25 child recall
-> vector child recall
-> RRF
-> optional rerank
-> parent aggregate/fetch
-> per-retriever threshold
-> result budget
```

关键点：

- BM25、cosine 和 RRF 分数语义不同，不能共用一个 threshold。
- topK 与 candidate window 分开；rerank 只处理有限候选。
- query rewrite 必须保存原 query、改写 query 和命中差异。
- 多个 child 命中同一 parent 时，要明确取最高分、加权聚合或仅去重。
- 先有 fixed corpus + human ground truth，再决定 rewrite/rerank 是否值得。

## 索引版本和重建

文档至少保存：

```text
document/knowledge_base/owner
parent_chunk_id/chunk_type/chunk_index
content/content_hash/source coordinates
parser_version/chunker_version
embedding_provider/model/dimensions
analyzer_version/retriever_version
```

推荐 versioned physical index + read/write alias。chunker、analyzer、embedding model/dimension 改变时新建索引、bulk 重建、离线评测、原子切 alias、保留旧索引回滚；不原地混用不同语义。

## 需要用户拍板

- A/B/C/D；
- standard/SmartCN/IK 的 baseline 和引入门槛；
- parent 聚合规则；
- threshold 是固定配置、按 retriever 分开，还是基于评测集校准；
- rewrite/rerank 是否进入首版；
- index alias 与版本命名规则。

## 一手来源

- [Elastic Chunking Overview](https://www.elastic.co/search-labs/blog/chunking-strategies-elasticsearch)
- [Semantic Chunking Cost Study](https://arxiv.org/abs/2410.13070)
- [Elasticsearch Join Field](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/parent-join)
- [Standard Analyzer](https://www.elastic.co/docs/reference/elasticsearch/analysis/analysis-standard-analyzer)
- [SmartCN](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)
- [Elastic Analysis Plugins](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-plugins)
- [Elasticsearch RRF](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)
- [Dense Vector](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/dense-vector)
