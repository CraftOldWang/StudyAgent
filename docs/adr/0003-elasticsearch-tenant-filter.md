---
status: accepted
---

# chunks-v1 同时按用户和知识库隔离

`chunks-v1` 的每个索引文档增加 `user_id` keyword；BM25、向量与父块二跳查询均由服务端注入 `user_id + knowledge_base_id` 过滤条件，模型不能提供或改写这两个权限参数。索引中冗余一个用户标识的成本很小，而检索请求可以在实际读取点同时执行用户与资源范围约束，不把隔离正确性完全寄托在一次查询前的数据库校验上。

## Considered Options

- ES 只保存 `knowledge_base_id`：服务端先在数据库验证知识库归属，再仅按知识库过滤 ES。字段较少，但要求所有入口都正确执行预校验，并让隔离依赖数据库校验与 ES 查询之间的跨存储约定。
- ES 同时保存 `user_id` 与 `knowledge_base_id`：数据有少量冗余，索引结构变化时需要重建；换取查询侧直接、统一、可测试的双重范围过滤，因此采用此方案。

## Consequences

- 所有索引写入路径必须写入 `user_id`，缺少该字段的文档不得进入 `chunks-v1`。
- 所有检索器及 parent 二跳必须同时过滤 `user_id` 与允许访问的 `knowledge_base_id`；数据库归属校验仍可保留，但不能替代查询过滤。
- 已按旧 mapping 创建的物理索引需要重建后再切换读写 alias。
