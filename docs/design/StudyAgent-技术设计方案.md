# StudyAgent 技术设计方案

**版本** v1.0  
**日期** 2026-09-02  
**状态** 待评审

---

## 1. 项目定位

一个**基于 AgentScope Java 2.0 的学习垂类 Agent**，重点在：
- RAG 策略对比与优化（结构分块 vs token window、混合检索、父子二跳）
- LLM-as-judge 端到端评测（Recall@K + 答案质量）
- 轻量知识图谱（实体-关系 + 前置知识回溯）
- 工具治理补强（写入配额、条数上限）

不是通用 Agent 框架。AgentScope 2.0.1 已证实提供 Loop、RuntimeContext/AgentState、Memory/Compaction、AgentStateStore、Trace exporter 与 Subagent 扩展点；checkpoint fork/replay 的恢复粒度尚未证明。我们专注于垂类问题、项目级映射与策略优化。

**Runtime 目标态**：AgentScope 是唯一 Agent Runtime。现有 Spring AI `ChatModel` / `ChatGenerationService` / `ToolCallbackProvider` 调用链仅作为换轴期间的迁移遗留存在，禁止新增消费者；AgentScope Harness、工具与学习流程消费者迁移完成后，由执行任务 3.12 统一删除。Spring AI Embedding 即使在 RAG 阶段暂留，也只是 provider adapter，不属于 Agent Runtime。

---

## 2. 技术栈

### 2.1 核心依赖

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | AgentScope 要求 |
| Spring Boot | 3.5.7 | 保持现有版本，不升级到 4.x |
| **AgentScope Harness** | **2.0.1** | Agent Runtime 基座 |
| AgentScope DashScope 扩展 | 2.0.1 | DashScope provider |
| AgentScope OpenAI-compatible 扩展 | 2.0.1 | DeepSeek provider（`deepseek:<model>`） |
| Spring AI | 1.1.x | 迁移期遗留；Chat/Tool 运行链由任务 3.12 删除，Embedding adapter 在 RAG 阶段单独决定 |
| MyBatis-Plus | 3.5.16 | 持久化 |
| Elasticsearch Java API Client | **8.15.x** | 替换手写 HttpClient |
| Redisson | 3.52.0 | 分布式锁、去重 |
| RocketMQ | 2.3.5 | 异步消息 |
| Canal | 1.1.8 | 数据补偿 |
| Tika | 3.3.0 | 文档解析 |
| AWS S3 SDK | 2.37.3 | 对象存储 |
| Flyway | - | 数据库版本 |

### 2.2 基础设施（docker-compose）

| 服务 | 版本 | 端口 | 用途 |
|------|------|------|------|
| MySQL | 8.1 | 3307 | 主数据库 |
| Redis | 7.4 | 6380 | 缓存、Redisson、Bitmap 断点续传 |
| Elasticsearch | 8.15.3 | 9200 | 向量 + BM25 检索 |
| Kibana | 8.15.3 | 5601 | ES 管理 |
| RocketMQ | 5.3.2 | 9876/10911/8080 | 消息队列 |
| RustFS | latest | 9000 | S3 兼容存储 |
| Canal | 1.1.8 | 11111 | MySQL binlog 订阅 |

---

## 3. 整体架构

### 3.1 分层视图

```text
┌─────────────────────────────────────────────────┐
│  前端 (React 18 + TS + Vite)                     │
│  知识库 / 学习会话 / Trace 时间线 / 画像展示        │
└─────────────────────────────────────────────────┘
                      ▼ HTTP
┌─────────────────────────────────────────────────┐
│  Controller 层 (各子域 web/ 包)                   │
│  agent/web  rag/web  ingest/web  learning/web   │
└─────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────┐
│  业务层 (子域服务)                                 │
│                                                 │
│  ┌──────────────┐  ┌──────────────┐            │
│  │   identity   │  │   learning   │            │
│  │  身份与隔离   │  │   垂类流程    │            │
│  └──────────────┘  └──────────────┘            │
│                                                 │
│  ┌──────────────┐  ┌──────────────┐            │
│  │     rag      │  │    ingest    │            │
│  │  检索与评测   │  │  摄入与分块   │            │
│  └──────────────┘  └──────────────┘            │
│                                                 │
│  ┌──────────────┐  ┌──────────────┐            │
│  │   profile    │  │     eval     │            │
│  │ 画像与图谱    │  │  离线评测     │            │
│  └──────────────┘  └──────────────┘            │
└─────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────┐
│  AgentScope Harness (运行时基座)                  │
│  Loop / Context / Memory / State / Trace hooks  │
│  Subagent / Skill / Workspace / Sandbox         │
└─────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────┐
│  工具治理补强层 (agent/governance)                 │
│  写入配额 / 条数上限 / 业务重试                     │
└─────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────┐
│  工具实现层                                        │
│  knowledge_search / review_card_write           │
│  用户画像查询 / 知识图谱遍历                        │
└─────────────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────┐
│  持久化层                                         │
│  model/ mapper/ (MyBatis-Plus)                  │
│  ES (检索索引)  Redis (缓存/去重)  S3 (文件)       │
└─────────────────────────────────────────────────┘
```

### 3.2 模块依赖图

```text
identity  ←──────────── 所有模块（数据隔离）
   ▲
   │
ingest ─────→ rag ────→ agent/governance ────→ learning
   │            │              │                    │
   │            │              │                    ▼
   │            │              └──────────────→ profile
   │            │
   │            └──────────────────────→ eval
   │
   └────────────────────────────────→ review

前端 ──→ agent/web, rag/web, learning/web, profile/web
```

**硬约束**：
- `agent` 不依赖 `learning`（运行时不认识"知识点"这类领域概念）
- `eval` 只被依赖方向的下游读取，不进请求路径（离线批量）

---

## 4. 核心模块设计

### 4.1 identity（身份与隔离）

**职责**：
- `users` 表
- 从请求头 / API token 解析身份
- **数据隔离的唯一强制点**（通过 MyBatis-Plus 拦截器或 AOP）

**不做**：注册、登录、密码、找回、RBAC

**关键类**：
- `IdentityResolver`：从 `HttpServletRequest` 解析 `userId`
- `DataIsolationInterceptor`：MyBatis-Plus 拦截器，自动注入 `user_id` 条件
- `User` 实体

**表**：
```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);
```

---

### 4.2 agent（运行时集成与工具治理）

**职责**：
1. **AgentScope 与 Spring Boot 胶水层**（`agent/integration`）
   - `HarnessAgent` 的 Spring 管理
   - `RuntimeContext` 构建（绑定 `userId` / `sessionId`）
   - Workspace 配置（本地文件系统）
   - Model 注册（DashScope / DeepSeek）

2. **工具治理补强**（`agent/governance`）
   - 写入配额：`review_card_write` 一次最多写 5 张卡
   - 写入条数上限：模型返回 200 张，只写前 5 张 + 记录警告
   - 业务级重试：检索超时重试 3 次，其他工具失败不重试

3. **学习垂类 skill 定义**（`agent/skill`）
   - Markdown skill 模板：讲解、答疑、测验生成、卡片生成
   - Subagent 执行契约：输入 schema、输出 schema、预算限制

**关键类**：
- `AgentScopeConfig`：配置 `HarnessAgent` bean
- `ToolGovernanceInterceptor`：工具调用拦截器，检查配额与条数
- `KnowledgeSearchTool`：包装 `rag` 模块的检索服务
- `ReviewCardWriteTool`：包装 `review` 模块的卡片写入服务
- `ExplainSkill` / `QuizSkill` / `CardSkill`：skill 模板

**不拥有**：AgentScope 已提供的 Loop、基础 Context/Memory/State 与 Trace 采集机制。checkpoint fork/replay、traceId 查询、业务映射与治理补强必须先按已证实扩展点实现或 Spike，不把未发现的框架 API 当成现成能力。

---

### 4.3 rag（检索与融合）

**职责**：
- 检索策略：BM25 / 向量 / RRF 融合
- 父子二跳：child 召回 → parent 获取
- 相关性阈值（BM25 / cosine / RRF 分开）
- 查询改写（可选，首版不做）
- 以工具形式暴露给 `agent`

**关键类**：
- `RetrievalService`：检索编排
- `BM25Retriever` / `VectorRetriever`：单策略检索
- `RRFusion`：RRF 融合（k=60）
- `ParentAggregator`：父块二跳
- `ElasticsearchIndexer`：使用 Elasticsearch Java API Client

**ES 索引结构**（versioned physical index + alias）：
```json
{
  "mappings": {
    "properties": {
      "user_id": {"type": "keyword"},
      "knowledge_base_id": {"type": "keyword"},
      "document_id": {"type": "keyword"},
      "chunk_id": {"type": "keyword"},
      "parent_chunk_id": {"type": "keyword"},
      "chunk_type": {"type": "keyword"},
      "chunk_index": {"type": "integer"},
      "content": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "keyword": {"type": "keyword"}
        }
      },
      "content_hash": {"type": "keyword"},
      "embedding": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      },
      "chunker_version": {"type": "keyword"},
      "embedding_model": {"type": "keyword"},
      "created_at": {"type": "date"}
    }
  }
}
```

**检索隔离**：所有索引写入都必须保存 `user_id`；BM25、向量与 parent 二跳查询由服务端同时注入 `user_id` 和允许访问的 `knowledge_base_id` 过滤条件，模型不得决定权限参数。数据库中的知识库归属校验可以保留，但不能替代 ES 查询自身的双重范围过滤（ADR-0003）。

**别名管理**：
- `chunks-v1-read` / `chunks-v1-write`：当前活跃索引
- 策略变更时新建 `chunks-v2`，bulk 重建，评测通过后原子切 alias

---

### 4.4 ingest（摄入与分块）

**职责**：
- 上传接口（分片、续传、去重）
- 对象存储（S3 适配）
- 格式解析（Tika）
- 文本清洗
- **分块**（结构切分 + token fallback）
- 嵌入调用
- 索引写入（通过 `rag` 模块）
- 管道状态机（PENDING / PARSING / CHUNKING / EMBEDDING / INDEXING / COMPLETED / FAILED）
- MQ 投递与 Canal 补偿
- **云端 ASR**（音视频）

**分块策略**（详见 D-011）：
1. 优先结构切分：按标题 / 章节 / 段落 / 列表 / 代码 / 表格
2. 超长块 fallback：paragraph → sentence → token window (900/120 for child, 2400/240 for parent)
3. 保留来源坐标：Phase 1 固定保存解析文本 offset 与标题路径；页码等仅在解析器能够提供时补充

**Phase 1 分块契约**：
- `StructuredChunker` 与 `TokenWindowChunker` 复用固定算法和版本的本地确定性 `TokenCounter`；窗口、重叠、`tokenCount` 与测试均使用该口径，不使用字符数估算，也不依赖 provider 请求后的 usage。
- 两级 chunker 统一输入/输出 `ChunkSegment(content, tokenCount, sourceLocation)`；`SourceLocation` 至少包含解析器输出文本的 `[startOffset, endOffset)` 与 `headingPath`，fallback 后必须保持原坐标系。
- token 计量算法变化时提升 `chunker_version`，重新分块、索引并运行检索回归（ADR-0002）。

**关键类**：
- `UploadController`：分片上传接口
- `S3ObjectStorageService`：S3 适配与分片
- `TikaDocumentParser`：格式解析
- `StructuredChunker`：结构切分
- `TokenWindowChunker`：token fallback
- `DocumentPipeline`：状态机编排
- `CloudASRService`：云端 ASR（阿里云 / 腾讯云）

**表**：
```sql
CREATE TABLE file_records (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,  -- UPLOADING / COMPLETED
    created_at DATETIME NOT NULL,
    INDEX idx_user_kb (user_id, knowledge_base_id)
);

CREATE TABLE documents (
    id BIGINT PRIMARY KEY,
    file_record_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(512),
    content_type VARCHAR(64),
    pipeline_status VARCHAR(32) NOT NULL,  -- PENDING / ... / COMPLETED / FAILED
    error_message TEXT,
    parser_version VARCHAR(32),
    chunker_version VARCHAR(32),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_user_kb (user_id, knowledge_base_id),
    INDEX idx_status (pipeline_status)
);

CREATE TABLE document_chunks (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_id VARCHAR(64) NOT NULL UNIQUE,
    parent_chunk_id VARCHAR(64),
    chunk_type VARCHAR(16) NOT NULL,  -- PARENT / CHILD
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    source_location JSON,  -- Phase 1: {startOffset, endOffset, headingPath}
    embedding_status VARCHAR(32),  -- PENDING / COMPLETED / FAILED
    indexed_at DATETIME,
    created_at DATETIME NOT NULL,
    INDEX idx_document (document_id),
    INDEX idx_parent (parent_chunk_id)
);
```

---

### 4.5 learning（学习垂类流程）

**职责**：
- 学习计划生成与推进
- 知识点生命周期（NEW / EXPLAINING / QUIZ / COMPLETED）
- 委派 subagent：讲解 / 答疑 / 测验生成 / 卡片生成
- 知识点完成后触发 AgentScope 的 context compaction
- 把学习信号投给 `profile`

**关键类**：
- `LearningPlanService`：计划生成
- `KnowledgePointLifecycle`：状态机
- `SubagentOrchestrator`：委派编排
- `LearningSignalPublisher`：向 `profile` 投递信号

**表**：
```sql
CREATE TABLE learning_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    agentscope_session_id VARCHAR(128) NOT NULL,  -- 对应 AgentScope sessionId
    status VARCHAR(32) NOT NULL,  -- ACTIVE / PAUSED / COMPLETED
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_user (user_id),
    INDEX idx_as_session (agentscope_session_id)
);

CREATE TABLE learning_plan (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    plan_json JSON NOT NULL,  -- [{topic, subtopics, estimatedMinutes}]
    created_at DATETIME NOT NULL,
    INDEX idx_session (session_id)
);

CREATE TABLE knowledge_points (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    topic VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,  -- NEW / EXPLAINING / QUIZ / COMPLETED
    started_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_session_status (session_id, status)
);
```

---

### 4.6 review（复习卡片）

**职责**：
- Anki 卡片模型
- 单向导出到 Anki（AnkiConnect adapter）

**不做**：FSRS 调度（D-005）

**表**：
```sql
CREATE TABLE review_cards (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_point_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    front TEXT NOT NULL,
    back TEXT NOT NULL,
    source_chunk_id VARCHAR(64),
    exported_to_anki BOOLEAN DEFAULT FALSE,
    anki_note_id BIGINT,
    created_at DATETIME NOT NULL,
    INDEX idx_user (user_id),
    INDEX idx_kp (knowledge_point_id)
);
```

---

### 4.7 profile（用户画像与知识图谱）

**职责**：
- 用户画像字段（学习偏好、难点、进度）
- **轻量实体-关系知识图谱**（从学过的知识点抽实体与前置关系）
- 可视化：前置知识回溯路径

**关键类**：
- `ProfileService`：画像查询与更新
- `EntityExtractor`：从知识点抽实体
- `RelationExtractor`：抽取前置关系
- `KnowledgeGraphTraversal`：图遍历（BFS 找前置）

**表**：
```sql
CREATE TABLE user_profiles (
    user_id BIGINT PRIMARY KEY,
    learning_style JSON,  -- {preferredPace, difficultyLevel, focusAreas}
    pain_points JSON,  -- [{topic, errorCount, lastErrorAt}]
    total_study_minutes INT DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE kg_entities (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    entity_name VARCHAR(256) NOT NULL,
    entity_type VARCHAR(64),  -- CONCEPT / ALGORITHM / TOOL / THEORY
    first_learned_at DATETIME NOT NULL,
    knowledge_point_id BIGINT,
    INDEX idx_user (user_id),
    UNIQUE INDEX idx_user_name (user_id, entity_name)
);

CREATE TABLE kg_relations (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    from_entity_id BIGINT NOT NULL,
    to_entity_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,  -- PREREQUISITE / EXTENDS / USES
    created_at DATETIME NOT NULL,
    INDEX idx_user_from (user_id, from_entity_id),
    INDEX idx_user_to (user_id, to_entity_id)
);
```

**不做**：图数据库、多跳推理（超出轻量范围）

---

### 4.8 eval（离线评测）

**职责**：
- **RAG 检索评测**：Recall@K，对比 BM25 / 向量 / 混合 RRF / 父子二跳
- **答案质量评测**：LLM as judge
- 人工标注黄金集（几十条）
- Judge 一致率校准
- 回归门槛：改动后分数不许掉

**关键类**：
- `RetrievalEvaluator`：Recall@K 计算
- `AnswerQualityJudge`：LLM judge（调用 DeepSeek）
- `GoldenSetManager`：黄金集管理
- `RegressionGate`：回归检查

**表**：
```sql
CREATE TABLE eval_golden_set (
    id BIGINT PRIMARY KEY,
    query TEXT NOT NULL,
    ground_truth_chunk_ids JSON NOT NULL,  -- [chunk_id1, chunk_id2, ...]
    expected_answer TEXT,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL
);

CREATE TABLE eval_runs (
    id BIGINT PRIMARY KEY,
    run_type VARCHAR(32) NOT NULL,  -- RETRIEVAL / ANSWER_QUALITY
    strategy_config JSON NOT NULL,
    metrics JSON NOT NULL,  -- {recall@3, recall@6, judge_score}
    created_at DATETIME NOT NULL
);

CREATE TABLE eval_judge_calibration (
    id BIGINT PRIMARY KEY,
    golden_item_id BIGINT NOT NULL,
    human_label INT NOT NULL,  -- 1-5
    judge_score FLOAT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_golden (golden_item_id)
);
```

---

## 5. 数据库表结构总览

**9 个业务表族 + AgentScope 运行数据（不等同于一个 MySQL 表族）**：

| 表族 | 表 | 说明 |
|------|---|------|
| identity | users | 用户表 |
| ingest | file_records, documents, document_chunks | 文件、文档、分块 |
| rag | （ES 索引） | 检索索引，versioned physical index |
| learning | learning_sessions, learning_plan, knowledge_points | 学习会话、计划、知识点 |
| review | review_cards | 复习卡片 |
| profile | user_profiles, kg_entities, kg_relations | 画像、图谱 |
| eval | eval_golden_set, eval_runs, eval_judge_calibration | 评测 |
| **AgentScope** | **（由框架及项目配置管理）** | **workspace session/memory、AgentStateStore、显式 trace exporter 输出** |

**AgentScope 2.0.1 当前已证实的默认持久化边界**：
- workspace 内的 `SessionTree` 日志与 memory 文件由 workspace 管理。
- 默认 `JsonFileAgentStateStore` 使用 `${user.home}/.agentscope/state/<agentId>`，设置 workspace 不会同步改变该目录。
- `AgentTraceMiddleware` 只写 SLF4J；结构化轨迹需要显式配置 `JsonlTraceExporter`。`SessionTree` 的 JSONL 不能直接等同于完整 trace。
- workspace、state store、trace exporter 的实际落盘位置，以及业务表需要保存哪些关联字段，均待用户决策和运行 Spike 后进入 V1 schema。

---

## 6. 关键技术决策与 tradeoff

### 6.1 为什么用 AgentScope 而不自建 Runtime

| 维度 | 自建 | AgentScope | 选择 |
|------|------|------------|------|
| Loop / Context / Memory / State / Trace 采集 | 从零实现，2-3 周 | 已有基础 API 与扩展点；checkpoint fork/replay、traceId 查询仍需项目验证 | ✅ AgentScope 基座 + 项目级补强 |
| 面试讲点 | "我造了个框架"（必输比较） | "我如何在通用 runtime 上构建垂类 + 优化策略 + 评测" | ✅ 后者更有差异化 |
| 工具治理 | 完全自定义 | 大结果 offload + 权限，配额/条数需自建 | ⚠️ 补强即可 |
| 时间预算分配 | Runtime 占 60%，垂类占 40% | Runtime 占 10%，垂类 + RAG + 评测占 90% | ✅ 后者高杠杆 |

### 6.2 Spring AI Chat/Tool 迁移边界与 Embedding 区分

AgentScope 已有 DashScope provider，以及 OpenAI-compatible 扩展中的 DeepSeek provider。目标态由 AgentScope 独立承担 Agent Runtime；现有 Spring AI `ChatModel`、`ChatGenerationService`、`ToolCallbackProvider` 及其消费者仅用于换轴期间维持未迁移链路，禁止新增消费者，不是长期双 Runtime 方案。待 AgentScope Harness 入口、工具、学习主流程、compaction 与 subagent 消费者迁移完成后，任务 3.12 删除该旧 Chat/Tool 运行链及无用依赖。

Spring AI Embedding 属于 RAG provider adapter，不负责 Agent Loop、工具调用或会话运行，因此不等同于 Agent Runtime，其保留或替换在 RAG 阶段单独决定，不纳入任务 3.12。AgentScope 模型 fallback 与重试是另一条配置边界，按任务 0.4 的已记录选择执行，不以 Spring AI 作为隐式降级链路。

### 6.3 ES 访问方式

**官方 Elasticsearch Java API Client** vs 手写 HttpClient：
- 官方 client 提供类型安全、请求构建器、异步支持、错误分类
- 现有 412 行手写 JSON 可维护性差
- **Tradeoff**：引入依赖 vs 长期维护成本，选择前者

### 6.4 中文分词器选型

**先 baseline 再择优引入**：
1. 用 `standard` analyzer 作为 baseline
2. 建立词法 Golden Cases（Java/AI 术语、中英混合、数字、缩写）
3. 建立 retrieval Golden Cases
4. 用 `_analyze` 对比 SmartCN / IK
5. 择优引入（SmartCN 官方插件 vs IK 社区插件）

**不直接凭名称选择。**

### 6.5 Skill vs Subagent

**单纯 Markdown skill 含金量低。采用 subagent 执行 + skill 定义规范 + 评测 skill 质量的组合**：
- Skill 是模板与契约（输入 schema、输出 schema、预算限制）
- Subagent 是执行者（`AgentSpawnTool.agentSpawn` 委派）
- 评测证明质量（judge 打分、与人工标注一致率）

---

## 7. 非功能需求

### 7.1 性能指标

| 指标 | 目标 | 备注 |
|------|------|------|
| 检索 P99 延迟 | < 500ms | BM25 + 向量混合 |
| 讲解一个知识点 | < 30s | 含检索 + 模型调用 |
| 测验生成（5 题） | < 45s | Subagent 同步等待 |
| 卡片生成（3 张） | < 20s | Subagent 同步等待 |

### 7.2 可用性

| 维度 | 要求 |
|------|------|
| Session 恢复 | 以 `AgentState` / `AgentStateStore` 做运行 Spike；重启恢复粒度与 checkpoint fork 尚未证明 |
| 失败可见 | pipeline_status 明确，错误信息记录 |
| Trace 审计 | 显式 `JsonlTraceExporter` 输出并由项目建立 traceId 映射/查询；保留策略待设计 |

### 7.3 扩展性

| 维度 | 方案 |
|------|------|
| 多租户 | 服务端注入权限条件；MySQL 按 `user_id` 隔离，ES 同时按 `user_id + knowledge_base_id` 过滤；workspace/state 路径隔离只作为补充 |
| 分布式 Session | 基于 `AgentStateStore` SPI 评估自建或额外依赖；当前 JAR 未发现 Redis / MySQL / PostgreSQL 实现 |
| 水平扩展 | 先验证 state store 与 workspace 跨进程语义，再决定部署方案 |

---

## 8. 安全与合规

| 维度 | 措施 |
|------|------|
| 身份鉴权 | 请求头 / API token 解析，不做完整登录产品 |
| 数据隔离 | MyBatis-Plus 自动注入 `user_id`；ES 检索服务端注入 `user_id + knowledge_base_id` 双重过滤 |
| 权限控制 | 工具权限服务端注入，模型不可篡改 |
| 敏感信息 | API key 环境变量，不入库不打日志 |
| 工具审计 | AgentScope trace 记录所有工具调用 |

---

## 9. 测试策略

| 层级 | 覆盖 | 工具 |
|------|------|------|
| 单元测试 | `algo/` 纯算法、工具治理逻辑 | JUnit 5 + Mockito |
| 集成测试 | RAG 检索、ingest pipeline | Testcontainers (ES / MySQL) |
| 评测 | Recall@K、judge 一致率 | 自建 `eval` 模块 |
| 端到端 | 学习一个知识点完整流程 | 手工 + 录制 trace 回放 |

---

## 10. 部署架构

### 10.1 开发环境

```text
本地开发：
- IDE 启动 Spring Boot
- docker-compose 启动 MySQL / Redis / ES / RocketMQ / RustFS / Canal
- AgentScope workspace 在本地文件系统；具体位置待用户决定，配置必须解析为真实 `Path` 并显式创建目录，不能把 `~` 原样传给 Builder

前端：
- Vite dev server (localhost:5173)
- 代理到后端 localhost:8080
```

### 10.2 生产部署（未来）

```text
Kubernetes:
- Spring Boot 打包为 Docker 镜像
- workspace 是否挂 PVC、state store 是否同盘管理待用户决定
- 若需要 Redis / PostgreSQL state store，须基于 SPI 自建或引入经验证实现；2.0.1 当前 JAR 未内置
- ES / RocketMQ / S3 托管服务
```

---

## 11. 开发路线图（见执行方案）

本节内容见单独的**执行方案文档**。

---

## 12. 附录

### 12.1 AgentScope 核心概念速查

| 概念 | 说明 |
|------|------|
| `HarnessAgent` | AgentScope 的主 Agent 类，封装 ReActAgent |
| `RuntimeContext` | 绑定 `userId` / `sessionId`；不携带 workspace |
| `AgentState` / `AgentStateStore` | 可保存 agent state；checkpoint fork/replay 需运行验证 |
| `JsonFileAgentStateStore` | 默认写入 `${user.home}/.agentscope/state/<agentId>`，与 workspace 分离 |
| `SessionTree` | workspace 内的 session/transcript/compaction JSONL 管理，不等同于完整 trace |
| `AgentTraceMiddleware` / `JsonlTraceExporter` | 前者写 SLF4J；后者显式输出结构化 JSONL，traceId 查询需项目实现 |
| `Memory` | 框架 memory 接口与 Harness 文件记忆能力；具体布局和策略以集成测试为准 |
| `CompactionConfig` / `CompactionMiddleware` | 可配置压缩；保留哪些业务信息必须通过 prompt 与测试验证 |
| `Skill` | Markdown 模板，成功模式自动保存 |
| `AgentSpawnTool.agentSpawn` | 委派 subagent 的 Java API |
| `AgentTool` / `Toolkit` | 工具定义与注册入口 |
| `NamespaceFactory` | 可参与 workspace 命名空间隔离，但不替代服务端权限校验 |

### 12.2 参考资料

- AgentScope Java 官方文档：https://java.agentscope.io/v2/en/
- AgentScope GitHub：https://github.com/agentscope-ai/agentscope-java
- Elasticsearch Java API Client：https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html
- Spring Boot 3.5 文档：https://docs.spring.io/spring-boot/docs/3.5.x/reference/html/
- MyBatis-Plus 文档：https://baomidou.com/

---

**文档结束**
