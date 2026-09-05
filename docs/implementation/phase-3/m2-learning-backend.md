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

Docker VM 曾发生全局 OOM。恢复后，应用以 `-Xmx384m` 在 8082 稳定启动并通过 session GET；Linux 全量测试
以唯一 `-Xmx512m` Maven JVM 串行执行，135 tests、0 failure、0 error、3 skipped，耗时 56.9 秒。
不得同时运行应用和 Maven 全量测试；这两个数值只记录本次验证有效的配置，不代表通用最小上限。

用户明确授权合成 fixture 片段发送 DeepSeek 后，旧 key 下同一 session 的 explain 请求到达 provider，但返回 HTTP 402
`Insufficient Balance`。trace `37f0020a-c24f-448e-9e37-e6ec3c9397c7` 真实记录 `MODEL_CALL/STARTED`
和 `FAILURE/FAILED`，GET 恢复确认 session 仍为 `ACTIVE`、首点仍为 `NEW`、quiz 为 null、cards 为空；
浏览器也复现该恢复路径。

换用本地新 key 后，原 session 的 explain 已真实成功：trace `d3cefacf-e10e-41cd-badd-102689815bd2`，
首点推进到 `EXPLAINING`。随后三次 quiz 均按失败语义留在该状态且没有落库测验：

- `dcf28fd5-f761-4361-8ae8-684bb1d098fd`：模型在合法数组前输出说明文字，严格首字符解析失败；
- `48d2953c-5cf5-4235-aefa-4dfce7357112`：模型调用默认 `memory_search`/`grep_files` 后未调用 transition；
- `1bce3044-b7c5-44d7-8365-097d477a3e47`：多余工具已关闭，但模型受失败历史影响误称 quiz 已完成，仍未调用 transition。

对应最小修复为：统一 `common/json` 读取器提取单个数组后继续做严格 JSON/题数/字段/来源校验；生产 Harness
关闭默认 filesystem、memory 和 shell 工具，实际仅保留业务 `knowledge_search`/`learning_state_transition` 与 Harness
必需的 `wait_async_results`/`load_skill_through_path`；quiz prompt 注入服务端权威当前状态，并明确失败历史不代表业务提交。
这些修复已通过定向离线测试，但尚未再次付费验证。当前真实状态仍为 session `ACTIVE`、首点 `EXPLAINING`、
quiz 为 null、cards 为空；本轮已发送 4 次学习 POST，完整闭环还需要 quiz、QUIZZING 答疑、submit 与 cards，
因此 M2 仍不标记完成。

当前本机保留已配置的 `study-agent-es` 与 `study-agent-m2-app`（应用映射 `8082:8082`、数据库
`study_agent_m1_e2e`）。依赖停止时，一条命令恢复：

```powershell
docker start study-agent-es study-agent-m2-app
```

DeepSeek 密钥只填写在仓库根目录、已被 Git 忽略的 `some_apiKey` 中：
`new_deepseek_apiKey: <新密钥>`。Spring 以 properties 语法导入这个无扩展名文件，tracked `application.yml`
不再包含 DeepSeek 密钥默认值；`DEEPSEEK_API_KEY` 环境变量仍可显式覆盖。宿主从仓库根启动时直接读取此文件。
应用容器也必须把主树 `D:\1Learningoutput\javabackend\StudyAgent` 挂载为 `/workspace` 并以 `/workspace`
为 working directory，才能读取同一份文件，无需维护容器内副本。当前 `study-agent-m2-app` 仍挂载旧的
`.codex/worktrees/m2-learning/.agentscope/workspace` 作为有效 AgentState 数据源；这个旧 worktree 子目录不能作为
废弃副本删除。当前应用源码已改挂主树并在 8082 READY。需要重建容器时，先停止并移除同名旧容器，然后执行：

```powershell
docker run -d --name study-agent-m2-app --network study-agent_study-agent-net -p 8082:8082 --mount "type=bind,source=D:\1Learningoutput\javabackend\StudyAgent,target=/workspace" --mount "type=bind,source=D:\1Learningoutput\javabackend\StudyAgent\.codex\worktrees\m2-learning\.agentscope\workspace,target=/workspace/.agentscope/workspace" --mount "type=volume,source=study-agent-maven-cache,target=/root/.m2" -w /workspace -e JAVA_TOOL_OPTIONS=-Xmx384m -e SERVER_PORT=8082 -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/study_agent_m1_e2e -e SPRING_DATA_REDIS_HOST=redis -e SPRING_DATA_REDIS_PORT=6379 -e STUDY_AGENT_ELASTICSEARCH_ENDPOINT=http://elasticsearch:9200 -e S3_ENDPOINT=http://rustfs:9000 -e ROCKETMQ_NAME_SERVER=rocketmq-namesrv:9876 -e STUDY_AGENT_CANAL_ENABLED=false maven:3.9.11-eclipse-temurin-21 mvn spring-boot:run
```

`study-agent.ai.chat.max-tokens=1800` 已通过 AgentScope `ModelCreationContext` 的 `GenerateOptions` 接入
DeepSeek 模型，不再只是未消费的旧配置。AgentScope Harness 仍按既有配置控制业务级重试，本批没有改变 transport 重试。

Linux 全量入口如下；必须先停止 `study-agent-m2-app`，并保持没有其它本项目 Maven JVM：

```powershell
docker run --rm --network study-agent_study-agent-net --mount "type=bind,source=D:\1Learningoutput\javabackend\StudyAgent,target=/workspace" --mount "type=volume,source=study-agent-maven-cache,target=/root/.m2" -w /workspace -e JAVA_TOOL_OPTIONS=-Xmx512m maven:3.9.11-eclipse-temurin-21 mvn test
```

## 合并前旧副本备份

用户授权后，主树 13 个未跟踪 Phase 3 旧副本逐文件搬到 `.codex/backups/m2-pre-merge/`，保留相对结构：

- 三个 `skills/{card,explain,quiz}/SKILL.md`；
- 三个 Phase 3 旧实现说明；
- `KnowledgePointLifecycle`、`KnowledgePointStatus`、`LearningPlanItem`、`LearningPlanService` 四个 Java 文件；
- `LearningSkillTemplateTest`、`KnowledgePointLifecycleTest`、`LearningPlanServiceTest` 三个测试文件。

搬移前后均校验 13/13 源/目标绝对路径边界、源存在与目标不存在；没有递归搬目录、覆盖备份或触及其它未跟踪内容。
