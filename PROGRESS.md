# StudyAgent 进展

本文件是唯一实时任务看板，只记录成果和当前状态。产品范围见 `docs/design/001-全局设计与范围.md`，当前架构见 `docs/design/StudyAgent-技术设计方案.md`。

状态：`[x]` 已完成并在当前基线验证 · `[~]` 已实现但尚未完成当前里程碑验收，或正在进行 · `[ ]` 未开始 · `[!]` 被用户决策或外部条件阻塞。

## 当前 Goal · 简历目标能力与真实验收（2026-09-08）

执行契约：[002-简历目标与验收计划.md](docs/design/002-简历目标与验收计划.md)。两份计划合并执行，第一批核心链路与第二批 Anki/ASR 全部纳入；以下阶段表示本次目标，后文 M0–M2 是历史验收记录。

- [x] 合并确认范围、阶段契约、统一切块实验与真实验收口径，并同步产品/架构文档入口。
- [~] M3：已保存优化前源码/参数基线，修正密钥加载、embedding 工作区端点和前端代理，建立独立 eval 环境；新增持久化模型调用账本、2000 次限额、embedding SDK usage 与汇总/API 烟测脚本。真实 PDF/PPTX 与 provider 已烟测；后续测试与构建状态见 M4。学习请求仍因未调用状态工具失败，完整 trace 与性能基线待补。实现与证据见 `docs/implementation/m3-runtime-and-measurement.md`。
- [x] M4：阶段可恢复入库、embedding 产物复用、逐项索引确认、执行令牌/租约与过期恢复已实现；真实 ES 写入故障及终止容器后的自动恢复通过，未知 usage 保留。该批报告合计176项、0 failure/error、3 skipped（一次旧映射失败后4项复验通过）；父块去重、4096正文预算和生产API四模式另30项回归、8次真实请求通过。18份课程资料与100题已冻结，六种配置120次开发检索完成，结构800/0、parent2400/0已选定。独立验证及正式RAG评测完成，TXT/Markdown与8文档并发首轮入库复验通过；见 `docs/implementation/m4-ingest-recovery.md`、`docs/implementation/m4-chunk-selection.md`。
- [~] M5：已实现持久化规划任务、按文档批次提取、稳定大纲 ID、习题依据与顺序任务、阶段输入指纹/尝试记录/执行权、会话并发创建去重。13项定向回归通过；v1真实链路5次调用完成且重复执行无新增调用，v2验证失败阶段恢复/上游复用并发现数量及重点语义问题，证据如实保留。v3修正已打包，真实模型验收被自动审批拒绝，待明确数据外传授权；自然对话、局部摘要与恢复尚待实现。见 `docs/implementation/m5-planning.md`。
- [ ] M6：SSE 与核心前端改进、原生分片上传/续传/并发去重、正式 RAG/压缩/上传报告及真实流程/故障验收。
- [ ] M7：Anki 单向导出，实际导入、重复导出与失败重试验收。
- [ ] M8：真实音视频转写，复用知识库链路完成检索与学习验收。
- [ ] 收尾：独立验收、文档一致性检查、证据归档，以及按实测结果改写简历与面试说明。

**下一步：** M4已完成，正式评测见 `docs/implementation/m4-rag-validation-report.md`，并发死锁修正与格式验收见 `docs/implementation/m4-concurrent-ingest.md`。60道可回答验证题中BM25 Hit@5为70%，VECTOR/RRF均91.7%；父块没有总体改善，自动回答评审有确认的漏判，不将其通过率当作准确率。LLM账本382/2000次尝试，两次早期失败usage未知。继续M5自然对话、局部摘要与恢复的本地实现；规划v3真实调用因自动审批拒绝暂停，未绕过。摘要/工具全链trace仍需补齐。上传/压缩性能基线未完成前不替换对应实现；Goal尚未完成。

**完成门槛：** M3–M8 全部真实验收，三类指标可复现，Anki 实际导入与 ASR 实际链路通过；不预设指标涨幅，不因计划落盘或第一批完成关闭 Goal。

## 当前基线

- [x] AgentScope Java 2.0.1 单 Runtime 方向、按子域分包和服务端权限注入已确定。
- [x] Phase 0、Phase 1 与 ingest V2 的既有实现已经提交；RAG 底层已有上传、解析、分块、索引和混合检索基础。
- [x] Phase 2 工具治理已纳入当前干净包结构并通过 M0 批次验证。
- [x] Agent hello 已作为 M0 成果接入 AgentScope；learning/compaction 已作为 M2 实现进入 `main`，真实成功闭环状态见 M2。
- [~] Phase 3 WIP 提交 `95af8b1` 保存在 `codex/phase3-wip`，仅作保留副本，不是当前编译或验收基线。
- [x] 旧 `modules/`、顶层 `infrastructure/`、兼容层、Spring AI 代码和旧 `frontend/` 已从当前基线清理。

## M0 · 干净切换与真实 hello

- [x] Phase 2 已提交成果已安全纳入 `main`；Phase 3 WIP 继续保留在 `codex/phase3-wip`。
- [x] 有价值的解析、对象存储、上传、MQ/Canal 与 embedding 链路已迁入目标包，旧 `modules/`、顶层 `infrastructure/` 与兼容层已删除；未跟踪文件未删除。
- [x] Spring AI 代码与依赖已删除；Agent Runtime 只保留 AgentScope，embedding 使用官方 `dashscope-sdk-java` 并保留 DOCUMENT/QUERY 语义。
- [x] S3 endpoint 占位符已修复，真实容器启动已成功创建/检查 bucket。
- [x] 初始用户为 `username='default-user'`；`users` 表显式使用 InnoDB、utf8mb4、`utf8mb4_0900_ai_ci`。
- [x] 用真实 DeepSeek 配置跑通 `POST /api/agent/hello`，服务端注入 `X-User-Id: 1` 后返回真实模型文本。
- [x] 实现者批次测试、Linux JDK21 全量验证、实现文档和独立 verifier review 均已完成。

## M1 · 真实 PDF 到 RAG 工具

- [x] 已实现最小知识库创建、列表、重命名和文档列表；本里程碑不做知识库删除。
- [x] 已用真实 PDF 跑通 RustFS 对象存储、RocketMQ、Tika 解析与 PARENT/CHILD 分块。
- [x] 已用官方 `dashscope-sdk-java` 跑通 DOCUMENT/QUERY embedding，并修正 AgentScope/SDK 之间的 Okio 二进制兼容与 SDK API base URL。
- [x] 已用真实索引跑通 Elasticsearch 写入、BM25/向量/RRF 和父块回填。
- [x] 已用稳定 AgentScope `AgentTool`/`Toolkit` 实现 `knowledge_search`；专用 Agent 仅暴露该工具，模型 schema 只含 query，user/KB 由服务端 scope 注入。
- [x] direct/Agent 响应返回 `chunkId`、`content`、`provenance`、`score`；`toolInvoked/hits` 来自真实工具执行，无结果不编造来源。
- [x] React 18 + TypeScript + Vite 知识库前端已全新重写，独立 review 通过并已集成至 `main`；未复活 M0 删除的旧前端。
- [x] M1 后端 Linux JDK21 全量 97 tests、真实 PDF/DeepSeek E2E 和独立 verifier review 已通过；前端自测与独立 review 已通过，实现文档已补，M1 已关闭。

## M2 · 单知识点学习闭环

- [x] 已实现并真实验证学习计划和 `NEW → EXPLAINING → QUIZZING → CARD_GENERATING → COMPLETED` 状态转换；失败留在当前状态，QUIZZING 中真实追问不回退。
- [x] 一个会话绑定一个用户、目标、知识库和 AgentScope session，同时只有一个活跃知识点；真实 session `2096054161353723906` 含五项计划，首点完成后第二点成为唯一 `NEW` 活跃点。
- [x] 已实现通过 `learningSessionId` 恢复；MySQL 保存业务事实及测验/反馈/卡片，AgentState 保存压缩后的模型上下文；失败恢复和完成后恢复均已真实通过。
- [x] 已实现并真实验证五题 JSON 聚合测验、一次提交评分与逐题反馈；不设首版及格门槛，本次测试作答为 100 分、5 条反馈。
- [x] 已真实生成并持久化三张带检索来源的复习卡片；本里程碑不接 AnkiConnect。
- [x] 知识点完成 turn 已按 one-off 契约真实调用 `compactIfNeeded`，4 条上下文压缩为 summary + 1 条保留消息并保存；容器重建后 SDK 从持久目录加载同一 AgentState，context 为 2。
- [x] 首版讲解、测验和卡片均由主 Agent 生成，不启用学习 subagent，真实 DeepSeek 验收通过。
- [x] 后端为 mutation 生成 traceId，并提供按 traceId 查询标准化时间线的 JSON API；不做 trace UI，失败、答疑、测验、评分、卡片、compaction 与完成事件均有真实证据。
- [x] 同步 REST 学习目标、计划、讲解/答疑、五题测验、反馈、卡片、状态与会话恢复页面已实现；前端自测、独立 review 与真实 UI 单知识点闭环通过，不做 SSE 或 trace UI。
- [x] 后端实现来自 `codex/m2-learning` 并已进入 main；36 项学习相关测试、`mvn compile`、独立 verifier review 及 Linux JDK21 全量 135 tests（0 failure、0 error、3 skipped）已通过，后续 Hook/清错/持久 stateStore 改动又通过 8 项定向回归。
- [x] DeepSeek 从 Git 忽略的根目录 `some_apiKey` 读取，tracked 配置不保存真实值；`max-tokens=1800` 已接入 AgentScope。真实检索命中后，per-subscription Hook 只在需要推进的当前 reasoning 指定 transition 工具；quiz `2096132226076696578`、答疑、评分、三卡与 compaction 均已成功。总计使用 9/10 次获授权学习操作，成功后停止，未使用第 10 次。
- [x] 用户已授权将 13 个未跟踪 Phase 3 旧副本可恢复搬移到 `.codex/backups/m2-pre-merge/`；13/13 源文件均已逐项校验并保留相对结构，未覆盖备份、未触及其它未跟踪内容。

## 本次 Goal 之外

- [ ] 用户画像、长期记忆和轻量知识图谱。
- [ ] 高级 checkpoint/replay、trace UI 与受 tool allowlist 限制的学习 subagent。
- [ ] 站内复习调度、Anki 复习反馈回流与计划联动。

## 项目同步

- [x] GitHub Project 停止维护；`PROGRESS.md` 取代其细粒度状态。
- [ ] 清理或 supersede 旧细粒度 Issues，只保留 M0/M1/M2 等少量模块级 Issues；仅在模块边界同步。
