# StudyAgent 进展

本文件是唯一实时任务看板，只记录成果和当前状态。产品范围见 `docs/design/001-全局设计与范围.md`，当前架构见 `docs/design/StudyAgent-技术设计方案.md`。

状态：`[x]` 已完成并在当前基线验证 · `[~]` 已实现但尚未完成当前里程碑验收，或正在进行 · `[ ]` 未开始 · `[!]` 被用户决策或外部条件阻塞。

## 当前基线

- [x] AgentScope Java 2.0.1 单 Runtime 方向、按子域分包和服务端权限注入已确定。
- [x] Phase 0、Phase 1 与 ingest V2 的既有实现已经提交；RAG 底层已有上传、解析、分块、索引和混合检索基础。
- [x] Phase 2 工具治理已纳入当前干净包结构并通过 M0 批次验证。
- [x] Agent hello 已作为 M0 成果接入 AgentScope；learning/compaction WIP 未进入 `main`。
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

- [~] 已实现学习计划和 `NEW → EXPLAINING → QUIZZING → CARD_GENERATING → COMPLETED` 状态转换；失败留在当前状态，QUIZZING 中追问不回退，本次已集成 main。
- [~] 一个会话绑定一个用户、目标、知识库和 AgentScope session，同时只有一个活跃知识点；真实 DeepSeek 已创建 session `2096054161353723906` 及五项计划。
- [~] 已实现通过 `learningSessionId` 恢复；MySQL 保存业务事实及测验/反馈/卡片恢复数据，AgentState 只保存最新短期上下文，真实首次讲解失败后首点保持 `NEW` 且 GET 恢复通过。
- [~] 已实现五题 JSON 聚合测验、提交评分与错题解释；不设首版及格门槛，待真实单知识点 E2E。
- [~] 已实现生成并持久化三张复习卡片；本里程碑不接 AnkiConnect，待真实单知识点 E2E。
- [~] 已在知识点完成 turn 结束处按技术设计的 one-off 强制契约调用 `compactIfNeeded`，处理 `Optional`、写回同一 AgentState 并保存；待真实 compaction 证据。
- [~] 首版已由主 Agent 完成讲解、测验和卡片生成，不启用学习 subagent，待真实模型验收。
- [~] 后端已生成 traceId，并提供按 traceId 查询标准化时间线的 JSON API；不做 trace UI，待真实链路查询证据。
- [~] 同步 REST 学习目标、计划、讲解/答疑、五题测验、反馈、卡片、状态与会话恢复页面已实现，前端自测和独立 review 已通过；仍待后端端到端联调，不做 SSE 或 trace UI。
- [~] 后端实现来自 `codex/m2-learning`（`85d62e7`，实现/验证文档检查点 `839fb51`）：36 项学习相关测试、`mvn compile`、独立 verifier review 及 Linux JDK21 全量 135 tests（0 failure、0 error、3 skipped）已通过；用户授权后真实 explain 已到达 DeepSeek，但 provider 返回 HTTP 402 `Insufficient Balance`，首点仍为 `NEW`，trace API 与浏览器失败恢复路径已通过；指定文件与运行配置使用同一 key，当前唯一运行阻塞是该账户余额，成功单点闭环仍未完成。
- [~] DeepSeek 已改为从 Git 忽略的 `config/local-secrets.properties` 或 `DEEPSEEK_API_KEY` 读取，tracked 配置不再保存真实默认值；本地文件尚未填写新 key、运行应用尚未重启，因此换钥尚未生效。
- [~] 用户已授权将 13 个未跟踪 Phase 3 旧副本可恢复搬移到 `.codex/backups/m2-pre-merge/`；13/13 源文件均已逐项校验并保留相对结构，未覆盖备份、未触及其它未跟踪内容。

## 后续 Goal

- [ ] AnkiConnect 单向导出与复习集成。
- [ ] 用户画像、长期记忆和轻量知识图谱。
- [ ] RAG 评测、黄金集、LLM-as-judge 和回归门槛。
- [ ] 高级 checkpoint/replay、trace UI 与受 tool allowlist 限制的学习 subagent。
- [ ] 音视频 ASR 与其它非首里程碑能力。

## 项目同步

- [x] GitHub Project 停止维护；`PROGRESS.md` 取代其细粒度状态。
- [ ] 清理或 supersede 旧细粒度 Issues，只保留 M0/M1/M2 等少量模块级 Issues；仅在模块边界同步。
