# StudyAgent 进展

本文件是唯一实时任务看板，只记录成果和当前状态。产品范围见 `docs/design/001-全局设计与范围.md`，当前架构见 `docs/design/StudyAgent-技术设计方案.md`。

状态：`[x]` 已完成并在当前基线验证 · `[~]` 已实现但尚未完成当前里程碑验收，或正在进行 · `[ ]` 未开始 · `[!]` 被用户决策或外部条件阻塞。

## 当前基线

- [x] AgentScope Java 2.0.1 单 Runtime 方向、按子域分包和服务端权限注入已确定。
- [x] Phase 0、Phase 1 与 ingest V2 的既有实现已经提交；RAG 底层已有上传、解析、分块、索引和混合检索基础。
- [~] Phase 2 工具治理实现提交 `ade98b9` 已在本地 `main`，但 `origin/main` 仍停在 `9a14ef3`；尚待纳入远端主线并在新架构下整体验收。
- [~] Agent hello 与 learning 的未跟踪 WIP 位于主工作树；不得把 WIP 状态写成已完成。
- [~] Phase 3 compaction WIP 提交 `ae5cd49` 保存在 `codex/phase3-compaction`，尚未进入 `main`，需按新学习流程选择性迁移或重写。
- [ ] 旧 `modules/`、顶层 `infrastructure/`、兼容层、Spring AI 代码和现有 `frontend/` 尚未完成清理或重写。

## M0 · 干净切换与真实 hello

- [ ] 把 Phase 2 已提交成果安全纳入远端 `main`，Phase 3 WIP 保留在独立分支。
- [ ] 迁移仍有价值的旧实现后，删除旧 `modules/`、顶层 `infrastructure/` 与兼容层；不删除任何未跟踪文件。
- [ ] 删除 Spring AI 代码与依赖，只保留 AgentScope 作为 Agent Runtime。
- [ ] 修复 S3 endpoint 启动配置问题。
- [ ] 创建 `username='default-user'` 的初始用户；`users` 表使用 InnoDB、utf8mb4、`utf8mb4_0900_ai_ci`。
- [~] 用真实 DeepSeek 配置跑通 `/api/agent/hello`；当前仅有未跟踪 WIP，尚未完成模块测试和独立 review。
- [ ] 完整批次测试通过并由独立 verifier review；模块完成后补实现文档。

## M1 · 真实 PDF 到 RAG 工具

- [ ] 实现最小知识库创建、列表、重命名和文档列表；本里程碑不做知识库删除。
- [~] 串起真实 PDF 上传至对象存储、Tika 解析、结构化分块；复核并迁移既有 ingest V2 成果。
- [ ] 用官方 `dashscope-sdk-java` 实现 DOCUMENT/QUERY 两种 embedding。
- [~] 串起 Elasticsearch 写入、BM25/向量/RRF 和父块回填；复核并迁移既有 Phase 1 成果。
- [ ] 实现 AgentScope `Knowledge` 适配与自定义 `AgentTool` `knowledge_search`；模型只提交 query，服务端绑定 user/KB scope。
- [ ] 返回 `chunkId`、`content`、`provenance`、`score`；无结果时明确无资料依据且不编造来源。
- [ ] 删除现有前端并以 React 18 + TypeScript + Vite 重写知识库创建、上传、文档状态和检索演示页面。
- [ ] 完整批次测试通过并由独立 verifier review；模块完成后补实现文档。

## M2 · 单知识点学习闭环

- [ ] 实现学习计划和 `NEW → EXPLAINING → QUIZZING → CARD_GENERATING → COMPLETED` 状态机；失败留在当前状态，QUIZZING 中追问不回退。
- [~] 迁移或重写当前 learning WIP：一个会话绑定一个用户、目标、知识库和 AgentScope session，同时只有一个活跃知识点。
- [ ] 通过 `learningSessionId` 恢复；MySQL 保存业务事实，AgentState 只保存最新短期上下文。
- [ ] 实现五题 JSON 聚合测验、提交评分与错题解释；不设首版及格门槛。
- [ ] 生成并持久化三张复习卡片；本里程碑不接 AnkiConnect。
- [~] 在知识点完成后的当前 Agent turn 结束处主动调用 public `ConversationCompactor`，用定制 `summaryPrompt` 压缩并保存 AgentState；现有独立分支 WIP 待迁移或重写。
- [ ] 首版由主 Agent 完成讲解、测验和卡片生成，不启用学习 subagent。
- [ ] 后端生成 traceId，并提供按 traceId 查询标准化时间线的 JSON API；不做 trace UI。
- [ ] 以同步 REST 重写学习目标、计划、讲解/答疑、测验、卡片和状态页面；不做 SSE。
- [ ] 完整批次测试通过并由独立 verifier review；模块完成后补实现文档。

## 后续 Goal

- [ ] AnkiConnect 单向导出与复习集成。
- [ ] 用户画像、长期记忆和轻量知识图谱。
- [ ] RAG 评测、黄金集、LLM-as-judge 和回归门槛。
- [ ] 高级 checkpoint/replay、trace UI 与受 tool allowlist 限制的学习 subagent。
- [ ] 音视频 ASR 与其它非首里程碑能力。

## 项目同步

- [x] GitHub Project 停止维护；`PROGRESS.md` 取代其细粒度状态。
- [ ] 清理或 supersede 旧细粒度 Issues，只保留 M0/M1/M2 等少量模块级 Issues；仅在模块边界同步。
