# StudyAgent 进展

本文件是唯一实时任务看板，只记录成果和当前状态。产品范围见 `docs/design/001-全局设计与范围.md`，当前架构见 `docs/design/StudyAgent-技术设计方案.md`。

状态：`[x]` 已完成并在当前基线验证 · `[~]` 已实现但尚未完成当前里程碑验收，或正在进行 · `[ ]` 未开始 · `[!]` 被用户决策或外部条件阻塞。

## 当前基线

- [x] AgentScope Java 2.0.1 单 Runtime 方向、按子域分包和服务端权限注入已确定。
- [x] Phase 0、Phase 1 与 ingest V2 的既有实现已经提交；RAG 底层已有上传、解析、分块、索引和混合检索基础。
- [x] Phase 2 工具治理已纳入当前干净包结构并通过 M0 批次验证。
- [x] Agent hello 已作为 M0 成果接入 AgentScope；learning/compaction WIP 未进入 `main`。
- [~] Phase 3 WIP 提交 `95af8b1` 保存在 `codex/phase3-wip`，尚未进入 `main`，需按新学习流程选择性迁移或重写。
- [x] 旧 `modules/`、顶层 `infrastructure/`、兼容层、Spring AI 代码和旧 `frontend/` 已从当前基线清理。

## M0 · 干净切换与真实 hello

- [x] Phase 2 已提交成果已安全纳入 `main`；Phase 3 WIP 继续保留在 `codex/phase3-wip`。
- [x] 有价值的解析、对象存储、上传、MQ/Canal 与 embedding 链路已迁入目标包，旧 `modules/`、顶层 `infrastructure/` 与兼容层已删除；未跟踪文件未删除。
- [x] Spring AI 代码与依赖已删除；Agent Runtime 只保留 AgentScope，embedding 使用官方 `dashscope-sdk-java` 并保留 DOCUMENT/QUERY 语义。
- [x] S3 endpoint 占位符已修复，真实容器启动已成功创建/检查 bucket。
- [x] 初始用户为 `username='default-user'`；`users` 表显式使用 InnoDB、utf8mb4、`utf8mb4_0900_ai_ci`。
- [x] 用真实 DeepSeek 配置跑通 `POST /api/agent/hello`，服务端注入 `X-User-Id: 1` 后返回真实模型文本。
- [~] 实现者批次测试与实现文档已完成；等待独立 verifier review 后关闭 M0。

## M1 · 真实 PDF 到 RAG 工具

- [ ] 实现最小知识库创建、列表、重命名和文档列表；本里程碑不做知识库删除。
- [~] 串起真实 PDF 上传至对象存储、Tika 解析、结构化分块；复核并迁移既有 ingest V2 成果。
- [x] 已用官方 `dashscope-sdk-java` 实现 DOCUMENT/QUERY 两种 embedding；M1 继续负责把它接入完整 PDF→RAG 验收链。
- [~] 串起 Elasticsearch 写入、BM25/向量/RRF 和父块回填；复核并迁移既有 Phase 1 成果。
- [ ] 以 `rag/retrieval` 应用服务和稳定的 AgentScope `AgentTool`/`Toolkit` 自定义 `knowledge_search`；M1 HTTP 入口验证并绑定 user/KB scope，模型只提交 query。
- [ ] 返回 `chunkId`、`content`、`provenance`、`score`；无结果时明确无资料依据且不编造来源。
- [~] 旧前端已在 M0 删除；React 18 + TypeScript + Vite 的知识库页面重写仍待 M1 实现。
- [ ] 完整批次测试通过并由独立 verifier review；模块完成后补实现文档。

## M2 · 单知识点学习闭环

- [~] 已实现学习计划和 `NEW → EXPLAINING → QUIZZING → CARD_GENERATING → COMPLETED` 状态机；失败留在当前状态，QUIZZING 中追问不回退，待真实链路验收。
- [~] 迁移或重写当前 learning WIP：一个会话绑定一个用户、目标、知识库和 AgentScope session，同时只有一个活跃知识点。
- [~] 已实现通过 `learningSessionId` 恢复；MySQL 保存业务事实及测验/反馈/卡片恢复数据，AgentState 只保存最新短期上下文，待真实链路验收。
- [~] 已实现五题 JSON 聚合测验、提交评分与错题解释；不设首版及格门槛，待真实模型验收。
- [~] 已实现生成并持久化三张复习卡片，来源缺失时明确为 `null`；本里程碑不接 AnkiConnect。
- [~] 在知识点完成 turn 结束处按技术设计的 one-off 强制契约调用 `compactIfNeeded`，处理 `Optional`、写回同一 AgentState 并保存；现有独立分支 WIP 待迁移或重写。
- [~] 首版已由主 Agent 完成讲解、测验和卡片生成，不启用学习 subagent，待真实模型验收。
- [~] 后端已生成 traceId，并提供按 traceId 查询标准化时间线的 JSON API；不做 trace UI。
- [~] 后端同步 REST 已覆盖学习目标、计划、讲解/答疑、测验、卡片和状态恢复；前端并行接入中，不做 SSE。
- [~] 定向 34 项测试已通过，完整 Maven、真实 MySQL/DeepSeek/RAG smoke 与独立 verifier review 待完成；实现与 REST 契约文档已补。

## 后续 Goal

- [ ] AnkiConnect 单向导出与复习集成。
- [ ] 用户画像、长期记忆和轻量知识图谱。
- [ ] RAG 评测、黄金集、LLM-as-judge 和回归门槛。
- [ ] 高级 checkpoint/replay、trace UI 与受 tool allowlist 限制的学习 subagent。
- [ ] 音视频 ASR 与其它非首里程碑能力。

## 项目同步

- [x] GitHub Project 停止维护；`PROGRESS.md` 取代其细粒度状态。
- [ ] 清理或 supersede 旧细粒度 Issues，只保留 M0/M1/M2 等少量模块级 Issues；仅在模块边界同步。
