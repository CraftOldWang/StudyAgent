# D-Q06：文件式短期/长期记忆与数据库分工

> 状态：READY_FOR_DECISION  
> 与 D-Q05、D-Q07、D-Q09、D-Q17 联合设计

## 不同对象

- Working memory：当前模型调用的有限上下文。
- Session memory：单个 session 的消息、工具、状态与恢复轨迹。
- Episodic learner memory：发生过的学习事件与证据。
- Semantic learner memory：跨会话稳定事实、偏好和当前画像。
- Artifact：长工具结果、计划、生成文件、导出物等大对象。
- Knowledge base：用户上传资料及派生索引，不属于个人记忆。
- Checkpoint：恢复运行时的快照，不是长期记忆。

## 方案

### A. DB/Event Store 为事实源 + 文件/对象工作区

DB 保存身份、状态、事件、provenance、权限、checkpoint 元数据和 artifact 引用；文件或对象存储保存大 payload、长摘要和生成物。

优点：事务、权限、审计和并发边界明确；文件仍便于工具和人工检查。

缺点：需要引用/hash、生命周期、垃圾回收与一致性策略。

### B. 全部放数据库

优点：查询、事务和权限统一。

缺点：大文本/文件膨胀，人工检查差；JSON/blob 迁移与备份成本高。

### C. 文件/JSONL 为事实源，DB 只做索引

优点：可 diff、fork、离线读，适合单机 Agent 工作区。

缺点：多用户、多进程、权限、原子提交、并发写和业务事务复杂；不适合直接承担 SaaS 业务事实。

### D. DB + 对象存储 + 向量派生索引

这是 A 的规模化扩展：大对象进对象存储，向量库仅作为 semantic retrieval 的派生索引。

## 推荐边界

推荐 A + D：

```text
DB/Event Store
  session/run/turn/step
  tool result metadata
  memory provenance/current state
  checkpoint metadata
  permission/version/idempotency
  artifact reference

File/Object Storage
  full context snapshot
  large tool result
  generated artifact/export
  optional human-readable workspace

Vector/Search Index
  knowledge and memory retrieval projection
  never the source of truth
```

文件记忆必须有 manifest：owner、scope、type、schema/version、source event、content hash、created/expires、sensitivity。Runtime 不能扫描到一个文件就自动放进 prompt。

`agent/memory` 只提供存取、选择、压缩和 provenance 机制；`profile` 才解释“用户掌握了什么”。

## 需要用户拍板

- A/B/C/D；
- 本地文件是正式 artifact store，还是只作为开发/工作区视图；
- 长 payload 首版放文件系统还是 S3 对象存储；
- semantic/episodic memory 是否都进入向量索引；
- memory 删除是 tombstone + 异步清理，还是物理立即删除；
- 哪些 memory 允许模型提出，哪些必须由业务证据产生。

## 一手来源与实现参照

- [MemGPT](https://arxiv.org/abs/2310.08560)
- [LangGraph Persistence](https://langchain-ai.github.io/langgraph/concepts/time-travel/)
- DeepSeek Harness session types：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/types.ts:230`
- Pi session tree：`D:/1Learningoutput/javabackend/pi/packages/coding-agent/src/core/session-manager.ts:844`
