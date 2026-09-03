# StudyAgent 执行方案

**版本** v1.0  
**日期** 2026-09-02  
**状态** 待评审

---

## 1. 执行原则

### 1.1 优先级排序

1. **先骨架后血肉**：先搭通一条端到端链路（上传 → 检索 → agent 调用 → 返回），再补完整性
2. **先评测后优化**：先建立 baseline 与评测，再做策略对比
3. **先单机后分布式**：先验证本地 workspace 与 JSON state store；分布式 state store 方案在 SPI 与跨进程语义验证后由用户决定
4. **先手工后自动化**：CI/CD 在功能稳定后补

### 1.2 质量门槛

每个阶段交付物必须满足：
- ✅ 编译通过（`mvn clean compile`）
- ✅ 单元测试通过（覆盖核心逻辑）
- ✅ 集成测试通过（覆盖主链路）
- ✅ 文档同步（API 变更、配置变更记录在案）

---

## 2. 五阶段路线图

### 阶段 0：基础设施与骨架（1 周）

**目标**：搭好开发环境，完成 AgentScope 集成骨架，能跑通一个最简 agent。

#### 任务清单

| ID | 任务 | 产出 | 验收 |
|----|------|------|------|
| 0.1 | 引入 AgentScope 依赖 | `pom.xml` 新增 `agentscope-harness` 2.0.1 + DashScope / OpenAI-compatible 扩展 | 依赖下载成功 |
| 0.2 | 配置 AgentScope + Spring Boot 集成 | `AgentScopeConfig.java` 显式创建 `HarnessAgent` bean；builder 注入 `Model` 与 workspace `Path` | 使用 stub Model 的 Bean 测试通过；生产 Bean 所需依赖边界明确 |
| 0.3 | 配置 workspace 本地文件系统 | 类型化 workspace `Path` 配置，启动时显式 `Files.createDirectories(...)`；不原样传入 `~` | 临时目录集成测试证明目录创建及 Builder 接收路径 |
| 0.4 | 配置 DashScope / DeepSeek provider | `DashScopeModelProvider` 与 OpenAI-compatible 扩展中的 `DeepSeekModelProvider`；registry 分别使用 `dashscope:<model>` / `deepseek:<model>` | ModelRegistry 可解析两类 provider；最终模型 ID 与远端调用另行验收 |
| 0.5 | 实现 `IdentityResolver` | 从请求头 `X-User-Id` 解析 userId | 解析成功 |
| 0.6 | 实现 `DataIsolationInterceptor` | MyBatis-Plus 拦截器自动注入 `user_id` 条件 | SELECT 查询自动带 `user_id` |
| 0.7 | 创建 `users` 表与初始数据 | Flyway 迁移 `V1__create_users.sql` | 表创建 + 插入 user_id=1 测试用户 |
| 0.8 | 端到端测试：最简 agent | POST `/api/agent/hello` 调用 `HarnessAgent.call("hello")` | 返回模型回复 |

0.2 的生产 Bean 同时依赖 0.3 的 workspace 与 0.4 的 Model；0.2 / 0.3 / 0.4 是合并还是重排待用户决定。任务 0.4 只配置 AgentScope 原生 provider，不提前删除尚有消费者的迁移期 Spring AI Chat/Tool 遗留；AgentScope 仍是唯一目标 Runtime，旧 Chat/Tool 运行链在任务 3.12 统一清理，Embedding adapter 的去留在 RAG 阶段单独决定。

**验收标准**：
- `mvn clean compile` 通过
- `AgentScopeConfig` bean 加载成功，日志显示 workspace 路径
- `/api/agent/hello` 返回模型回复，日志记录 userId=1

---

### 阶段 1：RAG 检索链路（2 周）

**目标**：搭通 ingest → ES 索引 → 检索 → 返回 的全链路，建立 baseline。

#### 任务清单

| ID | 任务 | 产出 | 验收 |
|----|------|------|------|
| 1.1 | 引入 Elasticsearch Java API Client | `pom.xml` 新增依赖，`ElasticsearchConfig.java` 创建 client bean | client 连接 ES 成功 |
| 1.2 | 设计 ES 索引结构 | `chunks-v1` 物理索引 + `chunks-v1-read/write` 别名 | 索引创建成功 |
| 1.3 | 实现 `ElasticsearchIndexer`（替换手写 HttpClient） | 使用官方 client 的 `IndexRequest` / `BulkRequest` | bulk 写入成功 |
| 1.4 | 实现 `BM25Retriever` | ES `match` 查询 + `_score` 排序，服务端注入 `user_id + knowledge_base_id` 过滤 | 返回 topK；跨用户或未授权知识库 chunk 不可命中 |
| 1.5 | 实现 `VectorRetriever` | ES `knn` 查询 + cosine 相似度，服务端注入 `user_id + knowledge_base_id` filter | 返回 topK；跨用户或未授权知识库 chunk 不可命中 |
| 1.6 | 实现 `RRFusion` | k=60 融合 BM25 + 向量结果 | 融合后返回 topK |
| 1.7 | 实现 `ParentAggregator` | child 召回 → 根据 `parent_chunk_id` 查 parent | 返回 parent 内容 + child provenance |
| 1.8 | 实现 `RetrievalService` | 编排 BM25 / 向量 / RRF / 父子，暴露统一接口 | 单测覆盖四种策略 |
| 1.9 | 实现 `StructuredChunker` | 按标题 / 段落 / 列表切分，返回 `ChunkSegment` | Markdown 分块保留 `[startOffset,endOffset)`、`headingPath` 与统一 `tokenCount` |
| 1.10 | 实现 `TokenWindowChunker` | 使用固定版本本地 `TokenCounter` 对超长 `ChunkSegment` 做 900/120 fallback | 单测证明 token 上限与重叠使用同一计量口径，且 fallback 不丢失来源坐标 |
| 1.12 | 表迁移：ingest 三张表 | `V2__create_ingest_tables.sql` | 表创建成功 |
| 1.12a | 新增 ingest 全局 `model/mapper` | 在全局 `model/`、`mapper/` 新增严格对应 V2 的 `FileRecord` / `Document` / `DocumentChunk` 及其 Mapper；暂不修改旧消费者或删除旧六类型 | 新六类型字段与 Mapper SQL 不引用 V2 外列；legacy 链保持原样；任务只标记实现完成、等待与 1.11 同批验收 |
| 1.11 | 实现并切换 `DocumentPipeline` | 使用新全局模型完成 PARSING → CHUNKING → EMBEDDING → INDEXING，切换必要消费者后清理旧六类型 | 状态、重试、版本和确定性 `chunk_id` 符合最小契约；不新增按 document 删除旧 ES；与 1.12a 同批验收 |
| 1.13 | 端到端测试：上传 PDF → 检索 | POST `/api/files/upload` + POST `/api/rag/search` | 检索返回相关段落 |
| 1.14 | 构建检索黄金集 | 从已索引 chunk 反向合成 100-200 条查询（ground truth = 源 chunk id），人工抽检 20% 剔除坏例；另自标注 30-50 条真实查询 | 黄金集入库 `eval_golden_set` |
| 1.15 | 中文分词器三方对比 | standard / IK / SmartCN 在黄金集上的 BM25 Recall@K 对比，择优引入 | 对比报告 + 决策记录（简历 B2 数字来源） |
| 1.16 | 相关性阈值实验 | 多档阈值跑 precision-recall 曲线，按检索器分别选点 | 曲线 + 选定阈值（简历 B2 数字来源） |

**1.12a / 1.11 迁移边界**：执行顺序固定为 `1.12（Issue #20）→ 1.12a → 1.11（Issue #19）`。1.12a 以 V2 schema 为唯一字段基线，只新增三实体与三 Mapper，不修改 legacy 消费者、不删除旧六类型、不实现 pipeline；`KnowledgeBase`、`UploadSession` 等其他实体/Mapper也不在范围内。1.11 使用新模型接线、迁移 V1 仍需保留的直接消费者并删除旧六类型；不借切换重写上传、S3、MQ、Canal 等工程件。两项属于同一验收批次，1.12a 中间态不可部署或独立关闭（ADR-0004）。

**1.11 最小契约**：业务 `chunk_id = SHA-256(documentId + chunkerVersion + chunkType + chunkIndex + contentHash)`；写入 `parser_version=tika-3.3.0`、`chunker_version=structured-jtokkit-cl100k-v1` 和实际配置的 `embedding_model`。任一阶段失败统一为 `FAILED` 且 `error_message` 带失败阶段；MQ/显式调用可将 `FAILED` 条件 claim 到 `PARSING`，Canal 只 claim `PENDING`。失败重试复用确定性 chunk ID 覆盖写，不增加按 `document_id` 删除旧 ES 的流程。

**验收标准**：
- 上传一份 Java 教程 PDF，pipeline 走完，ES 有数据
- 检索"什么是多态"，返回相关段落，`parent_chunk_id` 正确
- 单测覆盖 BM25 / 向量 / RRF / 父子四种策略

**Baseline 数据**：
- 在黄金集（任务 1.14）上跑 Recall@3 / Recall@6，记录四种策略 baseline 分数（BM25 / 向量 / RRF / 父子）

---

### 阶段 2：Agent 工具集成与治理（1.5 周）

**目标**：把检索包装为工具，补强工具治理，agent 能调用检索。

#### 任务清单

| ID | 任务 | 产出 | 验收 |
|----|------|------|------|
| 2.1 | 实现 `KnowledgeSearchTool` | 按 AgentScope `AgentTool` / `Toolkit` 公开 API 注册，参数 `String query`，调用 `RetrievalService` | agent 可调用 `knowledge_search` |
| 2.2 | 实现 `ReviewCardWriteTool` | 参数 `List<CardDraft>`，写入 `review_cards` 表 | agent 可调用 `review_card_write` |
| 2.3 | 实现 `ToolGovernanceInterceptor` | 拦截工具调用，检查配额（`review_card_write` 最多 5 张/次） | 超配额返回错误 + 记录警告 |
| 2.4 | 实现写入条数上限逻辑 | `review_card_write` 模型返回 200 张，只写前 5 张 + 日志警告 | 日志显示"截断至 5 张" |
| 2.5 | 实现业务级重试 | 检索超时重试 3 次，其他工具失败不重试 | 单测覆盖重试逻辑 |
| 2.6 | 表迁移：review 与 learning | `V3__create_review_learning_tables.sql` | 表创建成功 |
| 2.7 | 端到端测试：agent 调用检索 + 写卡片 | agent 对话"教我 Java 多态"，调用 `knowledge_search` 后生成卡片 | trace 显示工具调用，卡片入库 |
| 2.8 | 治理效果量化 | 统计配额拦截案例；对比体积治理前后单次工具调用 token 用量 | 数据入 `docs/eval/`（简历 B5 数字来源） |

**验收标准**：
- agent 对话触发 `knowledge_search`，返回检索结果
- agent 生成 3 张卡片，`review_cards` 表有 3 行
- 模拟返回 10 张卡片，只写入 5 张，日志有警告
- 显式 `JsonlTraceExporter` 的结构化输出包含本任务所需工具调用字段；`AgentTraceMiddleware` 的 SLF4J 日志与 `SessionTree` JSONL 不作为替代

---

### 阶段 3：学习流程与 Subagent 委派（2 周）

**目标**：实现学习计划 → 知识点推进 → subagent 生成测验/卡片的完整流程。

#### 任务清单

| ID | 任务 | 产出 | 验收 |
|----|------|------|------|
| 3.1 | 实现 `LearningPlanService` | 输入学习目标，调用模型生成计划 JSON | 返回 `[{topic, subtopics, estimatedMinutes}]` |
| 3.2 | 实现 `KnowledgePointLifecycle` | 状态机：NEW → EXPLAINING → QUIZ → COMPLETED | 状态正确流转 |
| 3.3 | 定义 `ExplainSkill` Markdown 模板 | `workspace/skills/explain.md`，包含输入/输出 schema | AgentScope 可加载 skill |
| 3.4 | 定义 `QuizSkill` Markdown 模板 | 生成 5 道选择题，schema 定义题目/选项/答案/解析 | 同上 |
| 3.5 | 定义 `CardSkill` Markdown 模板 | 生成 3-5 张 Anki 卡片，schema 定义 front/back/source | 同上 |
| 3.6 | 实现 `SubagentOrchestrator` | 使用 `AgentSpawnTool.agentSpawn` 同步委派 | subagent 返回结构化结果 |
| 3.7 | 实现知识点完成后 context compaction | 使用 `CompactionConfig` / `CompactionMiddleware` 配置并触发 | 用固定输入断言目标/状态/发现等项目必需信息的实际保留结果 |
| 3.8 | 实现 `LearningSignalPublisher` | 向 `profile` 投递学习信号（知识点完成、测验结果） | MQ 消息投递成功 |
| 3.9 | 端到端测试：完整学习一个知识点 | "教我 Java 泛型" → 讲解 → 测验 → 卡片 → 完成 | 状态流转正确，trace 完整 |
| 3.10 | 测验自一致性检查 | 模型只看源 chunk 回答自己出的题，答错即判不合格打回重出 | 接入生成流程，拦截案例可查（简历 B3 支撑） |
| 3.11 | 串行 vs 委派 A/B | 对比主对话阻塞时间、主 agent context 规模 | 数据入 `docs/eval/`（简历 B4 数字来源） |
| 3.12 | 清理旧 Spring AI Chat/Tool 运行链 | 删除旧 `ChatModel` / `ChatGenerationService` / `ToolCallbackProvider` 消费者、实现与不再使用的依赖；不包含 Embedding adapter | 代码搜索无 Spring AI Chat/Tool 消费者，相关模块通过完整批次的编译、测试与独立验收 |

任务 3.12 依赖 0.2 的 AgentScope Harness 入口、2.1-2.7 的工具迁移，以及 3.1-3.10 的学习主流程、compaction 与 subagent 迁移完成；依赖未满足前，旧链路只允许维持现有消费者，不得新增调用。

**验收标准**：
- 输入"学习 Java 面向对象"，返回 3-5 个知识点计划
- 推进第一个知识点：讲解 → 生成 5 道题 → 生成 3 张卡片
- 显式 trace exporter 经运行测试证明能建立所需 parent → child lineage；若框架字段不足，停下交由用户决定补强范围
- Context compaction 触发且压缩质量测试通过；memory 文件变化按实际 Harness 文件布局验收
- `learning_sessions` / `knowledge_points` / `review_cards` 表数据正确
- Spring AI Chat/Tool 旧运行链及无用依赖已删除；Embedding adapter 不在本阶段清理范围

---

### 阶段 4：用户画像与轻量知识图谱（1.5 周）

**目标**：从学习信号构建画像，抽实体-关系，可视化前置知识路径。

#### 任务清单

| ID | 任务 | 产出 | 验收 |
|----|------|------|------|
| 4.1 | 实现 `ProfileService` | 接收学习信号，更新 `user_profiles` | 画像字段更新正确 |
| 4.2 | 实现 `EntityExtractor` | 从知识点文本抽实体（调用模型，prompt 定义实体类型） | 抽出 CONCEPT / ALGORITHM / TOOL |
| 4.3 | 实现 `RelationExtractor` | 抽取前置关系（PREREQUISITE / EXTENDS / USES） | 三元组入库 |
| 4.4 | 实现 `KnowledgeGraphTraversal` | BFS 遍历找前置路径 | 给定实体返回前置依赖树 |
| 4.5 | 表迁移：profile 三张表 | `V4__create_profile_tables.sql` | 表创建成功 |
| 4.6 | 前端：画像展示页 | 显示学习偏好、难点、总时长、知识图谱 | UI 渲染正确 |
| 4.7 | 前端：知识图谱可视化 | D3.js / ECharts 绘制实体-关系图 | 点击实体显示前置路径 |
| 4.8 | 端到端测试：图谱构建 | 学完 3 个知识点，抽出 5-10 个实体 + 关系 | 前端显示图谱 |
| 4.9 | 图谱章节序一致性检查 | 前置边与教材章节序一致比例的自动指标（前置边理应大多指向更早章节） | 指标可复算（简历 B7 数字来源） |
| 4.10 | 音视频 ASR 对接 | ffmpeg 抽音轨 → 云端录音文件识别（异步批量接口），知识库术语作为热词表提升术语识别率 | MP4/MP3 走完 pipeline，文稿带句级时间戳 |
| 4.11 | 转写文稿时间戳分块 | 口语清洗（呃/就是说/重复句）后按时间窗聚合（child≈1min / parent≈5min），`source_location` 存 `{startMs, endMs}`；原始转写保留 | 检索命中带时间戳 |
| 4.12 | 前端播放定位 | 检索结果点击跳转到视频对应时间点 | 播放器 seek 正确 |

**验收标准**：
- 学完"Java 泛型"，`kg_entities` 有"泛型""类型擦除""通配符"等实体
- `kg_relations` 有"类型擦除 PREREQUISITE 泛型"等关系
- 前端点击"泛型"，高亮显示前置路径：面向对象 → 类与对象 → 泛型

---

### 阶段 5：评测与策略对比（1.5 周）

**目标**：建立 RAG 评测体系，对比策略，量化优化效果。

#### 任务清单

| ID | 任务 | 产出 | 验收 |
|----|------|------|------|
| 5.1 | 实现 `RetrievalEvaluator` | 计算 Recall@K（K=3, 6），支持 BM25 / 向量 / RRF / 父子 | 单测覆盖四种策略 |
| 5.2 | 实现 `AnswerQualityJudge` | LLM judge prompt，打分 1-5，评估答案正确性/依据性/无编造 | 调用 DeepSeek 返回分数 |
| 5.3 | 实现 `GoldenSetManager` | 管理人工标注集，CSV 格式 `query, ground_truth_chunk_ids, expected_answer` | 导入 20 条黄金集 |
| 5.4 | 实现 `RegressionGate` | 新旧版本对比，分数不许掉 | 掉分则失败并输出 diff |
| 5.5 | 表迁移：eval 三张表 | `V5__create_eval_tables.sql` | 表创建成功 |
| 5.6 | 跑 baseline 评测 | BM25 / 向量 / RRF / 父子，各跑 Recall@3 / Recall@6 | 记录 baseline 分数 |
| 5.7 | 对比中文分词器 | `standard` vs SmartCN vs IK，建立词法 Golden Cases | 择优引入（记录决策） |
| 5.8 | Judge 一致率校准 | 人工标注 20 条答案质量（1-5 分），计算 judge 与人工一致率 | 一致率 > 70% 合格 |
| 5.9 | 端到端评测报告 | 输出 Markdown 报告：策略对比 + judge 校准 + 回归结果 | 报告入库 `docs/eval/` |
| 5.10 | 模拟学生长会话批跑 | LLM 扮演学生（persona：会追问、会答错题、会跑题）驱动完整多轮学习会话，测会话完成率、终止正确性、compaction token 节省、崩溃恢复成功率（kill -9 实测） | 数据入 `docs/eval/`（简历 B1 数字来源） |

**验收标准**：
- Recall@6 对比：BM25 < 向量 < RRF < 父子二跳（假设，以实际为准）
- Judge 一致率 > 70%，记录偏好（长答案偏好 / 位置偏好）
- 改动分块参数后跑回归，分数不掉则通过
- 评测报告包含图表、表格、结论

---

## 3. 里程碑与时间线

| 阶段 | 周数 | 交付物 | 里程碑 |
|------|------|--------|--------|
| 阶段 0 | 1 周 | AgentScope 集成骨架 + 最简 agent | ✅ 基础设施就绪 |
| 阶段 1 | 2 周 | RAG 全链路 + baseline 评测 | ✅ 检索链路通 |
| 阶段 2 | 1.5 周 | 工具集成与治理 | ✅ Agent 可调用检索 |
| 阶段 3 | 2 周 | 学习流程 + subagent 委派 + 旧 Chat/Tool 运行链清理 | ✅ 垂类流程完整 |
| 阶段 4 | 2.5 周 | 画像 + 知识图谱 + 音视频 ASR | ✅ 画像/图谱/ASR 可用 |
| 阶段 5 | 1.5 周 | 评测体系 + 策略对比 | ✅ 量化优化效果 |
| **总计** | **10.5 周** | **完整系统** | **MVP 就绪** |

---

## 4. 人员分工（假设 2-3 人团队）

### 角色定义

| 角色 | 职责 | 阶段分工 |
|------|------|----------|
| **后端开发 A** | AgentScope 集成、工具治理、学习流程 | 阶段 0、2、3 |
| **后端开发 B** | RAG 检索、分块策略、评测 | 阶段 1、5 |
| **全栈开发 C** | 前端、画像与图谱、端到端测试 | 阶段 4、协助 1-3 端到端 |

如果只有 1 人，按阶段顺序执行即可。

---

## 5. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| AgentScope 与 Spring Boot 集成坑 | 中 | 高 | 阶段 0 提前验证，社区求助 |
| 中文分词器效果差 | 中 | 中 | 先 baseline，再对比择优 |
| Subagent 委派性能差 | 低 | 中 | 限制并发数，监控耗时 |
| 评测黄金集不够 | 高 | 中 | 边开发边积累，先 20 条再扩充 |
| Judge 一致率低 | 中 | 高 | 校准 prompt，必要时人工介入 |
| Context compaction 丢失关键信息 | 中 | 高 | 自定义 compaction prompt，单测覆盖 |

---

## 6. 交付清单

### 6.1 代码仓库

```text
src/main/java/com/studyagent/
  ├── agent/              # AgentScope 集成 + 工具治理
  ├── rag/                # 检索与融合
  ├── ingest/             # 摄入与分块
  ├── learning/           # 学习流程
  ├── review/             # 复习卡片
  ├── profile/            # 画像与图谱
  ├── eval/               # 评测
  ├── identity/           # 身份与隔离
  ├── config/             # 配置
  ├── common/             # 通用工具
  ├── model/              # 表实体
  └── mapper/             # MyBatis Mapper

src/main/resources/
  ├── application.yml     # 配置文件
  └── db/migration/       # Flyway 迁移脚本

src/test/java/            # 单元测试 + 集成测试

frontend/                 # React 前端

workspace/                # AgentScope workspace 示例（gitignore；实际位置待用户决定）
  ├── sessions/           # SessionTree 日志，非完整产品 trace
  ├── memory/             # Harness 文件记忆，实际布局以集成测试为准
  └── skills/             # Markdown skill 模板

${user.home}/.agentscope/state/<agentId>/
                          # JsonFileAgentStateStore 默认目录；与 workspace 分离，是否改址待用户决定

docs/
  ├── design/             # 设计文档
  ├── eval/               # 评测报告
  └── api/                # API 文档
```

### 6.2 文档交付

| 文档 | 路径 | 状态 |
|------|------|------|
| 技术设计方案 | `docs/design/StudyAgent-技术设计方案.md` | ✅ 已交付 |
| 执行方案 | `docs/design/StudyAgent-执行方案.md` | ✅ 已交付 |
| API 文档 | `docs/api/` | 阶段 2 后交付 |
| 评测报告 | `docs/eval/baseline-report.md` | 阶段 1 交付 |
| 策略对比报告 | `docs/eval/strategy-comparison.md` | 阶段 5 交付 |
| 部署文档 | `docs/deploy.md` | 阶段 5 后交付 |

### 6.3 数据交付

| 数据 | 格式 | 数量 |
|------|------|------|
| 黄金评测集 | CSV | 20+ 条（阶段 1），扩充至 50+ 条（阶段 5） |
| Baseline 分数 | JSON | 4 策略 × 2 指标（Recall@3/6） |
| Judge 校准数据 | CSV | 20 条人工标注 + judge 分数 |

---

## 7. 后续演进方向（MVP 之后）

| 方向 | 优先级 | 说明 |
|------|--------|------|
| **分布式 Session** | P0 | 基于 `AgentStateStore` SPI 评估自建或引入实现；当前 2.0.1 JAR 未发现 Redis / PostgreSQL store |
| **查询改写** | P1 | 扩展查询、同义词、拼写纠正 |
| **多模态检索** | P2 | 图表、公式、代码片段 |
| **自适应分块** | P2 | 根据文档类型智能选 window |
| **增量索引** | P2 | 文档更新时只重建变更部分 |
| **多角色协商** | P3 | Coach / Critic 多 agent（超出当前需求） |

---

## 8. 成本估算

### 8.1 开发成本

- 1 人全职：9.5 周
- 2 人并行：6 周（阶段 1、2、3 可部分并行）
- 3 人并行：5 周

### 8.2 基础设施成本（开发环境）

| 资源 | 配置 | 月成本（估算） |
|------|------|---------------|
| 本地 docker-compose | 16GB RAM | $0 |
| DeepSeek API | 按量付费 | ~$20-50 |
| DashScope API | 按量付费 | ~$30-60 |
| 云端 ASR（后续） | 按量付费 | ~$10-30 |

### 8.3 生产环境成本（未来）

- K8s 集群：2C4G × 3 节点 ~$100/月
- ES 托管：3 节点 ~$150/月
- RDS MySQL：2C4G ~$80/月
- Redis：2G ~$30/月
- S3 存储：100GB ~$5/月
- **总计** ~$365/月

---

## 9. FAQ

### Q1: 为什么不用 LangChain？

**A**: AgentScope 是国产、有 Java 原生支持、文档完整、社区活跃。LangChain 的 Java 绑定（LangChain4j）成熟度低于 AgentScope Java 2.0。

### Q2: Spring AI 会不会和 AgentScope 冲突？

**A**: 不会形成长期双 Runtime。AgentScope 是唯一目标 Agent Runtime；现有 Spring AI Chat/Tool 调用链仅在消费者迁移期间兼容存续，禁止新增消费者，并由任务 3.12 在 Harness、工具和学习流程迁完后删除。Spring AI Embedding 若暂留只是 RAG provider adapter，其去留在 RAG 阶段单独决定。AgentScope 不会因多个 provider 共存自动降级，fallback 与重试按任务 0.4 的已记录选择执行。

### Q3: 为什么不做 FSRS 调度？

**A**: D-005 已定，复习数据不作为画像必要输入。自建完整复习闭环体量太大，后续可通过 AnkiConnect 从 Anki 拉取统计数据。

### Q4: 评测黄金集从哪来？

**A**: 边开发边积累。阶段 1 先手工标注 20 条，阶段 5 扩充至 50+ 条。后续可用模型辅助生成 + 人工审核。

### Q5: Subagent 会不会太慢？

**A**: 同步委派确实会增加延迟（测验生成 ~45s，卡片生成 ~20s）。可接受范围内。如果用户反馈慢，再升级为异步 job + 进度查询。

### Q6: Context compaction 会不会丢信息？

**A**: AgentScope 提供 `CompactionConfig` / `CompactionMiddleware`，但不会凭 API 签名保证固定保留目标/状态/发现/计划。项目必须用明确 prompt、固定输入和断言验证压缩质量；回溯依赖显式 trace exporter 与项目保留策略，不能把 `SessionTree` JSONL 当成完整 trace。

### Q7: 知识图谱为什么不用 Neo4j？

**A**: 轻量实体-关系（几十到几百个实体）用 MySQL 够了，BFS 遍历性能可接受。只有规模到万级实体 + 多跳推理时才需要图数据库，当前不在需求范围内。

---

**文档结束**
