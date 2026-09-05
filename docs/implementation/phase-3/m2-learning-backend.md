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
错误记录使用条件 `UPDATE` 只写 `error_message` 和 `updated_at`，不把事务失败后内存中已变异的状态实体整体写回。

V6 migration 先以 nullable 列加入并回填 V3 历史行，再收紧非空和 `(session_id, sequence_no)` 唯一约束，
避免直接添加非空/唯一列导致旧会话升级失败。既有单数表名 `learning_plan` 保持不变，不做无关兼容性重命名。

## Agent 工具与来源边界

讲解、测验和卡片的 RuntimeContext 由服务端注入 user、knowledge base 和 knowledge point scope；模型不能选择权限 ID。
`KnowledgeSearchTool` 在当前 turn 的 RuntimeContext 中累计实际返回的 chunkId；讲解、测验和卡片必须发生检索，
测验/卡片的非空来源必须属于这组真实结果。`learning_state_transition` 只记录本 turn 允许的相邻目标；
Agent turn、输出解析与业务写入全部成功后，服务层才在事务内提交持久状态。

主学习 Agent 的业务工具集只暴露 `knowledge_search` 和 `learning_state_transition`，不暴露会提前落卡的
`review_card_write`。后者仍在服务端校验知识点必须处于 `CARD_GENERATING`，作为纵深门禁。

## Trace

每次 mutation 生成 UUID `traceId`，记录有序的 `MODEL_CALL`、`TOOL_CALL`、`STATE_TRANSITION` 或 `FAILURE` 事件。
`GET /api/learning/traces/{traceId}` 以当前用户过滤并返回标准化时间线。本阶段不实现 trace UI 或完整评测平台。

## 验证边界

单元测试覆盖状态推进、Agent transition tool、严格模型 JSON、五题无门槛评分、三卡持久化与失败重试、
one-off compaction、trace 排序/隔离、恢复 DTO 以及 Long ID 的 JSON string 契约。

M1 最终依赖合并后，宿主 JDK 21 的 36 项学习相关回归和 `mvn compile` 均通过，独立 reviewer 也确认
状态门禁、失败字段级回写、来源校验和共享检索证据引用。隔离库 `study_agent_m1_e2e` 已由 Flyway
校验 V1-V6；合成 KB `2096045355769974786` 只包含 M1 生成的测试 PDF，marker 为
`ORCHID-COMET-7319`，不包含私人文档。

真实 DeepSeek 已创建 session `2096054161353723906` 和五项计划。首次讲解中模型实际调用
`knowledge_search` 三次，但 AgentScope 派生 RuntimeContext 只复制 typed-value 映射，原实现替换映射值后
调用方无法观察，因而按失败语义停留首知识点 `NEW` 并可由 GET 恢复。当前实现改为 Harness 调用前预置同一个
mutable `KnowledgeSearchExecution`，工具只向该共享实例追加结果；定向测试与独立源码复核均通过。

Docker VM 随后发生全局 OOM，StudyAgent Elasticsearch 被杀。为避免影响其它项目，本批次不再重启 Docker/JVM；
同一 session 的讲解→五题→评分→三卡→单点完成真实 E2E，以及 Linux 容器全量 `mvn test`，仍需在环境恢复后执行，
因此 M2 暂不标记完成。恢复时使用 `X-User-Id: 1` 和上述 session，不创建重复会话。

当前本机保留已配置但停止的 `study-agent-es` 与 `study-agent-m2-app`（应用映射 `8082:8082`、
JVM `-Xmx384m`、数据库 `study_agent_m1_e2e`）。获得 Docker 恢复授权并确认内存后，一条命令恢复：

```powershell
docker start study-agent-es study-agent-m2-app
```

闭环完成并停掉应用后，Linux 全量入口为：

```powershell
docker run --rm --network study-agent_study-agent-net --mount "type=bind,source=D:\1Learningoutput\javabackend\StudyAgent\.codex\worktrees\m2-learning,target=/workspace" --mount "type=volume,source=study-agent-maven-cache,target=/root/.m2" -w /workspace maven:3.9.11-eclipse-temurin-21 mvn test
```
