# 决策记录

技术决策由用户做出（见 `AGENTS.md` §1）。本文件是决策的唯一记录处：待定的问题在下半部分，定了的移到上半部分并写清理由。

agent 可以往「待决策」区补充新问题、补充选项与代价分析，但**不得自行把待决策项标记为已决**。

---

## 已决策

### D-001 包结构：按子域分包，子域内按职责分包
**日期** 2026-09-01

废弃 `modules/<x>/{application,domain,infrastructure,interfaces}` 四层结构。通用的 `config`/`exception`/`response`/`model`/`mapper` 上提到 `studyagent/` 顶层；业务按子域（`agent`/`rag`/`ingest`/`review`/`profile`）分包；子域内部按职责分包（如 `agent/{loop,planner,writer,prompt,codec,tool,memory,checkpoint,trace,subagent,web}`）；纯算法集中在 `algo/`。

**为什么**：旧结构下 5 个超过 300 行的类全部堆在 `application/`，每个都同时承担编排、业务规则、持久化、DTO 组装、prompt 拼装、JSON 解析。原因不是分层规则不对（`interfaces/`→`infrastructure/` 的越界为零，规则守住了），而是 prompt、LLM JSON codec 这类东西**没有地方安放**，只能塞进 `application`。改成 `controller/service` 只是给垃圾桶改名——872 行的类依然无处可拆。按职责分包才让每一块有确定归属。

**代价**：不是标准套路，面试需要能解释「为什么按角色分」。

**备注**：`mapper`/`model` 全局共享是刻意的——旧结构把实体和 Mapper 关在模块私有包里，反而逼出 6 处「跨模块 import 对方 Mapper」和 `knowledge`↔`storage` 双向依赖。Controller 留在子域 `web/`。持久化全局、Web 局部，这个不对称是有理由的。

### D-002 旧代码的处置：新骨架 + 分域搬迁，不整体重写
**日期** 2026-09-01  
**修正** 2026-09-02

Agent 那部分事实上等于从零（现在基于 AgentScope，Loop、Context 预算、文件记忆、checkpoint、trace 全由框架提供）；但 ingest / 检索 / 前端三条链路是能跑且有测试的工程代码，重写纯亏。

- **直接搬**：`algo` 三块算法、Redisson 去重 + Redis Bitmap 断点续传、S3 适配与分片、MQ producer 的 afterCommit、Canal 补偿、docker-compose、前端 2449 行（含手写 SSE parser）、18 个测试类。
- **工程件搬、策略层重做**（2026-09-02 修正）：ingest / 检索的分片续传/去重/S3/MQ afterCommit/Canal 补偿保留，分块与检索策略重新设计（用户判断当前实现不符合需求）。
- **搬设计不搬实现**：工具权限注入模式（userId/KB 范围服务端注入、模型改不了）。这是旧代码里设计最对的一块。
- **重写**：`LearningAgentService`(872)、`ContextMemoryService` + 拍平字符串的 `contextText`、三份手工同步的 schema、`QuizService`(188，已不接入 agent)。基于 AgentScope 重写。
- **删除**：`FileUploadPerformanceService`(1169) + 9 个 `Performance*` DTO、`HashEmbeddingService`（删掉 `@Primary` 就会炸的死 bean）。
- **待议**：`ElasticsearchChunkIndexer`(412) 手写 HttpClient 裸 JSON —— D-010 已定，改用 Elasticsearch Java API Client。

### D-003 用户身份：真实身份，不做登录产品
**日期** 2026-09-01

有 `users` 表；身份从请求头 / API token 解析；数据隔离在**一处**统一强制，不散落到各个服务。不做注册、登录、密码、找回那一整套。

**为什么**：功能点 4（用户画像 + 长期记忆）需要一个真实的挂载主体，纯单用户会让画像无处可挂；但完整鉴权（Security + JWT + 注册登录）和本项目的主题（Agent 运行时）无关，是低杠杆投入。用户原话：上一整套鉴权是有点偏离项目的主题。

**取代**：`DEFAULT_USER_ID = 1L` 硬编码在 7 个服务里、全项目零鉴权的现状。

**面试可讲点**：隔离强制收在单点，而不是依赖每个服务自觉带 `user_id` 条件。

### D-004 `TextChunker` 不做 Spring 组件
**日期** 2026-09-01

去掉 `@Component` 与 `RagProperties` 注入，分块参数由调用方传入，退化为无状态纯算法，留在 `algo/chunk`。

**为什么**：`algo/` 的定位是不依赖 Spring、可纯单元测试。分块直接决定检索质量，而它现在**零测试**且边界吸附含硬编码 `start+200` 魔数，可测性值钱。

**注意**：分块的具体逻辑后续还要改（见 D-Q08），本次只改可测性，不动算法。

### D-005 FSRS：复习数据暂不作为画像的必要输入
**日期** 2026-09-01

复习数据不列为用户画像的必要输入。理由是自建完整复习闭环（调度 + 复习 UI + 记录）体量太大，与当前重点（Agent 运行时）不成比例。

后续可能的补法：通过 AnkiConnect 从 Anki **反向拉取统计数据**，而不是自己承担调度。这条留待验证——AnkiConnect 是本机 HTTP 服务（默认 8765），需要 Anki 桌面端在线，能不能稳定拿到「某张卡复习了几次、几次 Again」需要实测。

**影响**：`algo/fsrs`（`FsrsScheduler` 208 行 + 测试）暂时保留但不进主流程；功能点 4 的画像先用其他信号（学习轨迹、测验结果、答疑追问）构建，不依赖复习数据。原 D-Q00 三选项的分析保留在下方供后续回看。

### D-006 schema 压成干净的新 V1
**日期** 2026-09-01

废弃 V1–V7 全部迁移，重写为单个新 V1。不保留旧表结构，也不做兼容迁移。

**为什么**：仓库私有、9 个 commit、单用户、无生产数据，重开的代价接近零。而且后续表结构还要随 Agent 内核继续改，维护一条从旧结构演进过来的迁移链没有收益。

**注意**：这条解除了搬迁批次 2（`model/` + `mapper/`）的阻塞，但顺序上应当**先定表结构再搬**——否则搬完还要改一遍。

### D-007 压测与评测代码：删除，后续重做
**日期** 2026-09-01

压测代码（13 文件约 2146 行）与 `modules/evaluation`（约 1254 行）不保留，后续在功能设计清楚之后重做。

**为什么**：用户判断「目前功能都还没设计出来」，先有的测量与评测未必对得上重做后的功能。

**已知代价**（清理时已确认）：压测那套删除时必须成套处理——`MultipartUploadPart.java` 被 `ObjectStorageService`/`S3ObjectStorageService` 引用，前端有七处调 `/api/performance/*` 且 `App.tsx` 有整个 PerformanceView tab。multipart 存储能力本身是否值得单独保留，属独立问题。

### D-008 工作方式：先设计后实现，具体编码委托 codex
**日期** 2026-09-01

- 对某个模块做改动或加功能之前，**先出设计文档**，尤其是改动较大或涉及多个技术决策时。设计文档用于后续审查，也用于用户面试复习。
- 主线程只做设计讨论与决策；具体的编码与检查工作委托给 codex（`/codex:rescue` 等）。

**为什么**：设计文档留下的是「为什么这么做」，这正是面试要考的部分；而编码是可委托的重复劳动。主线程的上下文应当留给设计推理。

**设计文档放在仓库里，不放 Issue 正文**——理由见下方 D-Q16 的分析。

### D-009 技术栈：采用 AgentScope Java 2.0 作为 Agent Runtime 基座
**日期** 2026-09-02

**核心决定**：不自建 Agent Runtime，采用 AgentScope Java 2.0.1（`io.agentscope:agentscope-harness`）作为基座，专注于学习垂类、RAG 策略与评测。

**AgentScope 已提供**：Loop & 工具执行、结构化 context 压缩、双层 memory（日志 + 长期）、Session & Checkpoint（支持 Redis/MySQL/PostgreSQL）、完整 trace（`*.log.jsonl`）、子 agent 委派、Markdown skill 管理、多租户隔离、Sandbox（Docker/K8s/E2B）、中间件、Provider 抽象（DashScope/DeepSeek/OpenAI/Anthropic/Gemini/Ollama）。

**仍需自建**：
- 工具治理补强：配额（200 张卡只写 5 张）、写入条数上限、业务级重试策略（框架只重试模型调用）
- RAG 全链路：分块策略、检索融合、相关性阈值、评测
- 学习垂类流程：知识点生命周期、学习计划、讲解/答疑/测验/卡片的委派编排
- 用户画像与轻量知识图谱

**为什么不自建 Runtime**：
1. AgentScope 的 Loop / Context / Memory / Checkpoint / Trace 已经是生产就绪的实现，重写是低杠杆投入。
2. 面试讲点从"我造了个框架"（必输比较）转为"我如何在通用 runtime 上构建可评测的学习垂类 Agent"（有差异化的问题 + 有数字支撑的答案）。
3. 自建 runtime 会吃掉大部分时间预算，而 RAG 策略优化、评测、画像这三块才是简历亮点。

**与 Spring AI 的关系**（保留但降级为 provider adapter）：
- AgentScope 已有 DashScope/DeepSeek 扩展，但我们保留 Spring AI 1.1.x 作为备用 provider adapter（D-Q02 选项 C）。
- 如果 AgentScope 的 provider 扩展能满足需求，后续可移除 Spring AI 依赖。首版先两者并存，按需切换。

**模块调整**（详见重写后的 001）：
- `agent` 模块收缩为：工具治理补强 + AgentScope 与 Spring Boot 胶水层 + 学习 skill 定义。不再承担 Loop/Context/Memory/Checkpoint/Trace 的实现。
- `learning` 仍独立存在：知识点生命周期、学习计划、委派编排是垂类语义，不属于通用 runtime。

**简历口径**：
> 基于 AgentScope Java 2.0 构建学习垂类 Agent，重点在工具治理补强（写入配额/条数上限）、RAG 策略对比（结构分块 vs token window，BM25+向量混合检索，父子二跳）、LLM-as-judge 答案质量回归（Recall@K + 端到端评测）、轻量知识图谱（实体-关系抽取 + 前置知识回溯）。

不提"自研 runtime"，框架选型本身不是亮点；亮点是在框架上解决的垂类问题、用数据证明的策略优化、可追溯可回放的轨迹管理。

### D-010 ES 访问方式：Elasticsearch Java API Client
**日期** 2026-09-02

采用官方 **Elasticsearch Java API Client**，替换现有 412 行手写 `HttpClient` 裸 JSON。

**理由**：AgentScope 的 RAG 支持不含 ES 客户端（它只提供 agent runtime），ES 访问必须自建。官方 client 提供类型安全、请求构建器、异步支持、错误分类，比手写 JSON 可维护性高。

**保留**：现有索引映射与维度校验逻辑。

### D-011 分块与检索策略
**日期** 2026-09-02

**分块**：优先使用**结构切分**（按标题/章节/段落/列表/代码/表格切），超长块 fallback 到 token window + overlap。先选业界常用 size（child 900/120, parent 2400/240 保持），后续可尝试智能选取 window。

**检索**：父子检索策略 + BM25 + 向量混合（RRF 融合）。

**中文分词器**：先用 `standard` 作为 baseline，建立词法 Golden Cases（Java/AI 术语、中英混合）和 retrieval Golden Cases，再用 `_analyze` 对比 SmartCN/IK，择优引入。不直接凭名称选择。

**相关性阈值**：BM25 / cosine / RRF 分数语义不同，分开设阈值。先用固定配置，后续基于评测集校准。

**索引版本**：versioned physical index + read/write alias。chunker/analyzer/embedding model/dimension 变更时新建索引、bulk 重建、离线评测、原子切 alias、保留旧索引回滚。

详见 `.codex/decision-research/D-Q08-chunk-retrieval.md`。

### D-012 多 Agent 协作：同步扇出委派，首版进程内
**日期** 2026-09-02

采用 AgentScope 的 **同步进程内子 Agent 委派**（`agent_spawn` / `agent_send`）。

**形态**：
- 短 fan-out（测验生成、卡片生成）走同步等待，有界 fan-out、独立 session、结构化返回。
- 一开始就保留 task contract / lineage / budget 接口。
- 只有任务超过交互时延、需要重启恢复或跨实例扩展时，再升级为消息驱动 Worker（任务进队列、worker 领取）。

**子 Agent 不直接写业务状态**：只能返回 proposal / artifact，由父 Runtime / 业务服务验证后提交，或通过受治理写工具执行。`agent` 不应理解知识点/卡片业务语义。

**不做**：多角色讨论/投票网络（Coach / Teacher / Critic）。

**skill vs subagent 的使用**：
- 单纯 Markdown skill 含金量低。
- 采用 **subagent 执行 + skill 定义规范 + 评测 skill 质量** 的组合：skill 是模板与契约，subagent 是执行者，评测证明质量。

### D-013 Prompt 管理
**日期** 2026-09-02

使用 AgentScope 的 **Markdown skill 管理**，成功模式自动保存为 skill，跨 session 共享按需加载。

**不做**：独立版本化系统。AgentScope 的 skill 本身已支持文件形式 + 自演化。

### D-014 Trace 与回放
**日期** 2026-09-02

使用 AgentScope 的 trace 能力：
- 给每个 turn 绑定 traceId。
- **完整 trace** 落库为 JSON（`*.log.jsonl`，永不压缩）。
- **回放不重新调用模型和工具**，只展示运行轨迹（时间线 UI，参考 deepseek-harness）。

**从 checkpoint fork 重跑**：AgentScope 的 session 恢复 + StateModule 快照已支持。

**模型不确定性来源必须落库**：temperature、seed（provider 支持时）、模型版本——否则「重跑结果不同」无法区分改动生效与模型抖动。

**不做**（D-Q18）：工具结果复用记录 vs 重新执行的选择。首版不做重跑功能，只做轨迹查看。

### D-015 评测
**日期** 2026-09-02

**首版只做 RAG 评测**，因为整个学习流程的评测不太好做。

**包含**：
- 检索评测（Recall@K 多策略对比：BM25 / 向量 / 混合 RRF / 父子二跳）
- 答案质量评测（LLM as judge）
- 人工标注黄金集与 judge 一致率校准（几十条够）
- 回归门槛（改了 prompt 或分块参数，分数不许掉）

**离线批量**，不在请求路径上。

### D-016 音视频摄入：云端 API
**日期** 2026-09-02

**云端 ASR API**（具体选型见后续设计文档）。

**不做**：本地 Whisper（要处理模型、显存、长音频切分，与主题无关且极易吃掉数周）。

### D-017 异常体系
**日期** 2026-09-02

抛出通用异常即可。`algo/fsrs` 模块后续不使用，其对 `common/exception/BusinessException` 的依赖暂不处理。

### D-018 Issues + Project 迁移：两者分工
**日期** 2026-09-02

- **状态追踪**（决策的待定/已定、任务的进行中/待验收/完成、blocked-by 关系）→ Issues + Project。
- **设计文档**（D-008 要求的）→ **留在仓库内** `docs/design/NNN-主题.md`，走 PR 审查，从 Issue 链接过去。
- **决策记录**→ Issues 为准，但仓库内保留一份**由 Issues 生成的只读快照**（agent 每次动手都要读约束，`gh issue view` 是网络调用 + 需认证；codex 沙箱常常无网络）。

**标签**：除 `kind:decision` / `kind:task` 外，加 `area:agent` / `area:rag` / `area:ingest` / `area:review` / `area:profile`。

### D-019 表结构：全部可重新设计
**日期** 2026-09-02

V1–V7 全部废弃，重写为单个新 V1。不保留旧表结构，也不做兼容迁移。

**前置**：需先完成 Agent Runtime（基于 AgentScope）、学习流程、RAG、评测的详细设计，表结构是这些设计的产物。

### D-020 Controller 与 `web/` 包
**日期** 2026-09-02

各子域的 HTTP 入口（Controller、Request DTO、Response DTO）放在该子域的 `web/` 包下，例如 `agent/web/`、`rag/web/`。

**不提到顶层** `web/` 或 `controller/`，因为一个 Controller 只属于一个子域，提到顶层要么变成 1000 行大 Controller，要么就是放同一个包里但逻辑上各管各的——还不如直接放回子域。

**「持久化全局、Web 局部」是刻意的不对称**：`mapper` / `model` 全局是为了溶解旧结构逼出的跨模块 Mapper 依赖，Controller 局部是因为 HTTP 入口天然就该属于一个业务域。

备选方案：顶层 `web/` 下按子域再分一层（`web/agent/`、`web/rag/`），效果一样。

---

## 待决策（已清空，所有前置决策已完成）

### ~~D-Q00~~ 已决策 → D-005（以下分析保留供回看）
决定复习闭环的边界在哪，牵动 `review` 子域、`algo/fsrs`、以及功能点 1 的「闭环」到底闭在哪里。

实际有三个选项，不是两个：

- **甲 · 全自己做**（现状）：`algo/fsrs` 已有 `FsrsScheduler` 208 行 16 方法 + 测试，能跑。闭环完整、数据在自己库里、画像能直接用复习数据。代价：调度算法本身不是差异化能力，面试问「为什么自己实现 FSRS」不太好答，而且要自己做复习 UI。
- **乙 · 全推给 Anki**（AnkiConnect）：卡片生成后直接推进 Anki，调度与复习界面都不管。省掉一整块工作量，且 Anki 的复习体验远好于自建。代价：**复习数据回不来**，AnkiConnect 是本地 HTTP 服务（默认 8765），需要用户开着 Anki 桌面端，服务端拿不到复习结果——功能点 4 的画像就失去了最有价值的信号源（哪些知识点反复记不住）。另外这让「闭环」变成了「半环」。
- **丙 · 自己调度 + 单向导出**：调度和复习记录留在自己库里（保住画像信号），同时提供 Anki 导出/推送作为一个 adapter，用户想用 Anki 复习就导出。代价：两边可能不一致，要说清哪边是权威。

需要你定的其实是一个前置问题：**复习数据是不是画像的必要输入？** 如果是，乙就出局。

### D-Q01 ES 访问方式
现状是 `ElasticsearchChunkIndexer` 412 行手写 `java.net.http.HttpClient` 裸 JSON，项目里没有任何 ES client 依赖。要不要引入官方 client 或 Spring AI 的 vector store 抽象？索引映射与维度校验的逻辑要保留。

### D-Q02 技术栈：Spring AI 是否保留
现状 Spring AI 1.1.0 + DeepSeek/DashScope。

### D-Q03 Agent Loop 由谁拥有
现状：没有循环（一次请求 = PLAN→Planner→Writer 直线三步），工具轮次完全交给框架内置的 `DefaultToolCallingManager`，应用层看不见也管不住。`ToolCallingChatOptions.internalToolExecutionEnabled` 可以关掉、自己接管——这是轨迹可控与工具批量调用的前提。

### D-Q04 工具治理的具体形态
结果体积上限（现在父块 2400 字原样进 prompt）、写入条数配额（「1-5 张卡」只写在 prompt 里，模型交 200 张就写 200 行）、超时、重试。

### D-Q05 Context 管理与压缩
是否引入真正的消息列表、token 预算、分层压缩。现状：无 message list，历史被拍平成字符串塞进 user prompt，逐条截断 360 字但**条数无上限**；`token_count` 字段只写不读。

### D-Q06 记忆形态
文件形式的短期/长期记忆与数据库各承担什么。

### D-Q07 Session 与 checkpoint
恢复粒度、快照内容、崩溃后接管方式。现状不可恢复：`selectLatestCompleted` 写了但全项目没人调用，`runSession` 无 `@Transactional`，崩溃后 run 永远停在 RUNNING 且会被当僵尸 run 继续追加。

### D-Q08 分块与检索策略
分块参数与算法（含 `start+200` 魔数）、中文分词器（现在 ES `content` 用 `standard` analyzer，中文 BM25 退化成逐字匹配）、相关性阈值（现在没有阈值，topK=6 永远返回 6 条）。

### D-Q09 多 agent 协作模型
进程内并发还是消息驱动，子 agent 边界。现状为零：单模型三个 prompt 角色串行。

### D-Q10 Prompt 管理
模板放哪、是否版本化、结构化输出契约的唯一来源。现状全是 Java 字面量，决策 JSON schema 被手写三份靠人肉同步。

### D-Q11 Trace 与回放
traceId 传播方式、事件模型、回放需要落库哪些字段。现状无 traceId，`ObservationRegistry.NOOP` 三处显式传入丢掉了框架自带 observation。

### D-Q12 评测
LLM as judge 形态、轨迹评测口径。现状只评检索召回（Recall@K）。

### D-Q13 音视频摄入
ASR 方案。现状完全没实现，上传 MP3 会走到 Tika、解析不出文本、文档标 FAILED。

### ~~D-Q14~~ 已决策 → D-006（压成新 V1）
已确认可以重开。`agent_*`/`chat_*`/`learning_todos`/`quiz_*` 需随 Agent 内核重新设计；`file_records`/`documents`/`document_chunks` 可直接继承。压成一个干净的新 V1，还是保留 ingest 那几张、只重做 agent 那几张？依赖 D-003（已定）与 D-Q00。

### D-Q15 异常体系
`algo/fsrs/ReviewRating.java:3` 依赖 `common/exception/BusinessException`，是 `algo/` 里最后一条对外依赖。清掉它有两条路：给 `IllegalArgumentException` 补一个映射到 400 的 handler（顺手补上全局缺失的映射），或让服务层捕获后转成 `BusinessException`。不能直接换类型——`BusinessException` 映射为 HTTP 400 + 业务码 400，而 `IllegalArgumentException` 无 handler 会掉到 500 + 栈追踪，属于 API 契约变更。

### D-Q16 决策与进度的记录载体：是否迁到 GitHub Issues + Project
候选：维持仓库内 Markdown（现状）／迁到 Issues + Project／两者分工。

已确认的前置事实：仓库 `github.com/CraftOldWang/StudyAgent` 为**私有**（未认证访问 404），所以 Issues 也是私有的，放面试相关内容没有暴露问题。`gh` CLI 已装（2.83.1）但**认证 token 失效**，需先 `gh auth login` 才能用。

分工建议（待用户确认）：

- **状态追踪**（决策的待定/已定、任务的进行中/待验收/完成、blocked-by 关系）→ Issues + Project。看板确实比 Markdown 复选框好看，这是 `PROGRESS.md` 最弱的一环。
- **设计文档**（D-008 要求的那种）→ **留在仓库内**，`docs/design/NNN-主题.md`，走 PR 审查，从 Issue 链接过去。理由：Issue 正文没有 diff、没有行级评论、无法用 `file:line` 锚点、不能离线读、也不随代码一起版本化。一份 200-300 行的设计文档放 Issue 正文会很难审。
- **决策记录**→ Issues 为准，但仓库内保留一份**由 Issues 生成的只读快照**。理由：agent 每次动手都要读约束（`AGENTS.md` §1/§7 依赖它），而 `gh issue view` 是网络调用 + 需认证；codex 的沙箱常常无网络，读不到就等于没有约束。单向生成不产生双写维护。

需要修正的一处技术预期：`gh issue create/edit` 很方便，但**Project 字段更新与 issue 依赖关系（blocked by）大多要走 `gh api graphql`**，不是 `gh issue` 子命令能直接搞定的。仍然可行，只是脚本比「用现有 gh CLI 即可」更啰嗦一些。

建议补充的一项：标签除 `kind:decision`/`kind:task` 外，加 `area:agent`/`area:rag`/`area:ingest`/`area:review`/`area:profile`。面试复习时你会想按领域筛「所有 Agent 运行时的决策」，现在加便宜，回头补很烦。

### D-Q18 重跑时工具结果复用记录还是重新执行
由 D-009 §6 第 4 项引出。两种语义不同，落库要求也不同：

- **复用记录**（确定性重放）：只换 prompt 或模型，其余不变，能干净证明「是这次改动生效了」。代价是工具结果必须全量落库——单个父块 2400 字，体积增长快。
- **重新执行**：真跑一遍。但检索索引可能已变，结果不可比。适合验证修复是否生效。

大概率两种都要，需定**默认哪种**。另有一条无论如何都要做：**模型不确定性来源必须落库**（temperature、seed（provider 支持时）、模型版本），否则「重跑结果不同」无法区分改动生效与模型抖动。

并入 002 设计文档，与 D-Q07、D-Q11 一起定。

### D-Q17 新 V1 表结构设计
D-006 已定「压成新 V1」，但**表结构本身没设计**。这是搬迁批次 2（`model/` + `mapper/`）的真正前置。需要一并定的：身份表（D-003 已定方案）、Agent 事件/轨迹表（依赖 D-Q11）、记忆与 checkpoint 表（依赖 D-Q06、D-Q07）、画像表（依赖 D-005 已定的信号来源）。

按 D-008，这一项应当先出设计文档。
