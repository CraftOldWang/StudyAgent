# M2 单知识点学习闭环后端实现

## 纵向闭环

同步 REST 入口由 `LearningController` 提供。创建会话时 DeepSeek 生成有序计划并持久化 session、plan 与 knowledge points；
之后一次只操作 `activeKnowledgePointId` 指向的知识点。主 Agent 负责讲解、答疑、五题测验和三张卡片生成，
没有启用学习 subagent。完整 wire DTO 见 `m2-learning-rest-contract.md`。

测验以单个 JSON 数组保存恰好五道四选一题，提交五个答案后一次评分并保存逐题反馈，没有及格门槛。
复习卡固定生成并持久化三张；`sourceChunkId` 只有在数据库验证属于当前用户和知识库时保存，否则明确为 `null`。
刷新 GET session 会读取已有测验、评分反馈和卡片，不再次调用模型。

## 持久化与失败

MySQL 保存目标、计划、活跃知识点、解释、测验、反馈、卡片、业务状态和错误。
AgentScope `AgentState` 只保存同一个 `agentscope_session_id` 下的模型短期上下文。
每一步先校验当前 enum 状态；模型或工具失败写入当前 session/point 错误且不推进状态。

V6 migration 先以 nullable 列加入并回填 V3 历史行，再收紧非空和 `(session_id, sequence_no)` 唯一约束，
避免直接添加非空/唯一列导致旧会话升级失败。既有单数表名 `learning_plan` 保持不变，不做无关兼容性重命名。

## Agent 工具与来源边界

讲解、测验和卡片的 RuntimeContext 由服务端注入 user、knowledge base 和 knowledge point scope；模型不能选择权限 ID。
`knowledge_search` 返回的真实 chunk 才能成为来源。`learning_state_transition` 只记录本 turn 允许的相邻目标；
Agent turn、输出解析与业务写入全部成功后，服务层才在事务内提交持久状态。

## Trace

每次 mutation 生成 UUID `traceId`，记录有序的 `MODEL_CALL`、`TOOL_CALL`、`STATE_TRANSITION` 或 `FAILURE` 事件。
`GET /api/learning/traces/{traceId}` 以当前用户过滤并返回标准化时间线。本阶段不实现 trace UI 或完整评测平台。

## 验证边界

单元测试覆盖状态推进、Agent transition tool、严格模型 JSON、五题无门槛评分、三卡持久化与失败重试、
one-off compaction、trace 排序/隔离、恢复 DTO 以及 Long ID 的 JSON string 契约。
真实 MySQL migration、DeepSeek + scoped RAG 学习 smoke 和全量 Maven 结果在 M1 依赖合并后的批次结论中记录。
