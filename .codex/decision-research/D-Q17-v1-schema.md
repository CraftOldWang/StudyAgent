# D-Q17：新 V1 表结构的决策分解

> 状态：BLOCKED_WITH_RESEARCH  
> 本文不设计最终表；002 Runtime 决策完成后才能进入 schema 设计。

## 项目适配结论

当前只能确认表族、依赖和验证门，不能确认最终表名、字段或关系。D-Q17 的合理实施方式是等 002 固定 Runtime 语义后，再用本文的问题顺序产出 003 设计，而不是从旧表结构反推新 V1。

## 可以先固定的表族边界

- Identity：用户主体与请求身份引用；具体登录产品不在范围内。
- Ingest facts：文件、对象、文档、处理任务、artifact 引用。
- 通用 artifact metadata：owner、URI/key、hash、size、media type、schema/storage version、retention。

## 被前置决策阻塞的表族

| 表族 | 阻塞问题 |
| --- | --- |
| session/run/turn/step/event | D-Q03、D-Q07、D-Q09、D-Q11、D-Q18 |
| checkpoint/context | D-Q05、D-Q06、D-Q07 |
| prompt/tool | D-Q04、D-Q10、D-Q18 |
| memory/profile | D-Q06、学习证据来源 |
| rag/index metadata | D-Q01、D-Q08 |
| eval | D-Q12 |
| transcript/segment | D-Q13 |

## Event Payload 方案

### A. 全 JSON

灵活，但约束和查询弱；关键字段要通过 generated column 才能索引。

### B. 关键列 + JSON Payload

把 run/turn/seq/type/status/time/schema_version 等稳定字段列化，事件细节放 JSON。

优点：查询与扩展折中。缺点：需要维护列和 payload 的演进边界。

### C. 完全规范化

核心实体清晰，但事件种类增加时表数和迁移会膨胀。

推荐把 B 作为 event store 候选；稳定聚合实体仍使用规范化表。

## 大 Payload

- DB 内联：适合需要同事务提交的小型关键证据。
- 对象存储：适合完整 context、长 tool result、transcript、二进制 artifact。
- 混合：payload 小于阈值内联，否则保存 URI/hash/size/retention。

D-Q18 要求 replay 能证明结果完整，因此外置 payload 不能只留 URI，必须有 content hash、size、schema/version 和缺失语义。

## 003 设计文档提问顺序

1. 002 最终定义哪些 Runtime 概念和状态机？
2. 哪个聚合拥有状态，哪些只是 projection？
3. 恢复粒度是 run、turn、step 还是 tool call？
4. 哪些 event 必须 durable，哪些只是 OTel/live event？
5. replay mode 如何影响模型/工具结果存储？
6. ContextSnapshot 是否保存完整消息和编译来源？
7. prompt/tool/model 版本怎样被 Run 引用？
8. child agent 是否需要 child run、lineage、lease？
9. 再决定 JSON、外键、索引、唯一键和对象存储边界。

## 候选公共字段

以下只是 003 需要确认的候选，不是已定 schema：

```text
identity: user_id / owner_scope
relation: session_id / run_id / turn_id / step_id / event_id
ordering: seq / parent_id / attempt
idempotency: request_id / call_id / idempotency_key
state: status / failure_code / started_at / finished_at
version: schema/runtime/prompt/tool/model/provider version
lease: lease_owner / lease_until / row_version
context: snapshot_id / estimated_tokens / compaction boundary
replay: replay_mode / source result/event / result hash
retention: payload location / retention_class / expires_at
observability: trace_id / span_id / correlation_id
```

## 验证门

- 从零初始化和重复初始化；
- 身份隔离入口与索引；
- event seq、幂等键、状态转移和 checkpoint 重建；
- crash recovery 不重复执行副作用；
- ContextSnapshot 重建实际模型输入；
- recorded/live/mixed replay 可区分；
- DB/Object payload hash、size、URI 一致；
- trace/eval 能按 run/case/version 找到完整证据；
- 外键、唯一键、索引用真实并发与失败测试验证。

## 需要用户拍板

当前不应拍最终表结构。002 完成后依次决定：

- Event A/B/C；
- 大 payload DB/Object/混合；
- event seq 作用域；
- 外键使用范围；
- lease 是否首版需要；
- retention 与物理删除规则。

## 一手来源

- [MySQL JSON](https://dev.mysql.com/doc/refman/8.4/en/json.html)
- [MySQL Foreign Key Constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)
