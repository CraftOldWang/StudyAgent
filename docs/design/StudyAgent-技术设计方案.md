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

不是通用 Agent 框架。AgentScope 提供 Loop / Context / Memory / Checkpoint / Trace，我们专注于垂类问题与策略优化。

---

## 2. 技术栈

### 2.1 核心依赖

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | AgentScope 要求 |
| Spring Boot | 3.5.7 | 保持现有版本，不升级到 4.x |
| **AgentScope Harness** | **2.0.1** | Agent Runtime 基座 |
| AgentScope DashScope 扩展 | 2.0.1 | DashScope provider |
| AgentScope DeepSeek 扩展 | 2.0.1 | DeepSeek provider（通过 OpenAI 兼容） |
| Spring AI | 1.1.x | 备用 provider adapter（降级，可后续移除）|
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
│  Loop / Context / Memory / Checkpoint / Trace   │
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

**不拥有**：Loop / Context / Memory / Checkpoint / Trace（AgentScope 提供）

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
3. 保留来源坐标：章节号 / 页码 / 段落索引

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
    source_location JSON,  -- {chapter, page, paragraph}
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

**9 个业务表族 + 1 个 AgentScope 元数据表族**：

| 表族 | 表 | 说明 |
|------|---|------|
| identity | users | 用户表 |
| ingest | file_records, documents, document_chunks | 文件、文档、分块 |
| rag | （ES 索引） | 检索索引，versioned physical index |
| learning | learning_sessions, learning_plan, knowledge_points | 学习会话、计划、知识点 |
| review | review_cards | 复习卡片 |
| profile | user_profiles, kg_entities, kg_relations | 画像、图谱 |
| eval | eval_golden_set, eval_runs, eval_judge_calibration | 评测 |
| **AgentScope** | **（由框架管理）** | **workspace/agents/<agentId>/sessions/*.jsonl** |

**AgentScope 的持久化不入 MySQL**：
- Session 与 Memory 落盘为文件（`workspace/` 目录）
- Trace 落为 `*.log.jsonl`
- 我们只在业务表里记 `agentscope_session_id` 作为关联

---

## 6. 关键技术决策与 tradeoff

### 6.1 为什么用 AgentScope 而不自建 Runtime

| 维度 | 自建 | AgentScope | 选择 |
|------|------|------------|------|
| Loop / Context / Memory / Checkpoint / Trace | 从零实现，2-3 周 | 开箱即用 | ✅ AgentScope |
| 面试讲点 | "我造了个框架"（必输比较） | "我如何在通用 runtime 上构建垂类 + 优化策略 + 评测" | ✅ 后者更有差异化 |
| 工具治理 | 完全自定义 | 大结果 offload + 权限，配额/条数需自建 | ⚠️ 补强即可 |
| 时间预算分配 | Runtime 占 60%，垂类占 40% | Runtime 占 10%，垂类 + RAG + 评测占 90% | ✅ 后者高杠杆 |

### 6.2 Spring AI 保留还是移除

**保留但降级为备用 provider adapter**（D-009）：
- AgentScope 已有 DashScope / DeepSeek 扩展，首选它们
- Spring AI 1.1.x 保留作为 fallback
- 如果 AgentScope provider 扩展满足需求，后续可移除 Spring AI

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
- Subagent 是执行者（`agent_spawn` 委派）
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
| Session 恢复 | AgentScope checkpoint，重启后可恢复 |
| 失败可见 | pipeline_status 明确，错误信息记录 |
| Trace 审计 | `*.log.jsonl` 永久保留，可回溯 |

### 7.3 扩展性

| 维度 | 方案 |
|------|------|
| 多租户 | `user_id` 隔离 + AgentScope `NamespaceFactory` |
| 分布式 Session | AgentScope 支持 Redis / MySQL / PostgreSQL 后端 |
| 水平扩展 | 无状态 Controller + AgentScope StateModule |

---

## 8. 安全与合规

| 维度 | 措施 |
|------|------|
| 身份鉴权 | 请求头 / API token 解析，不做完整登录产品 |
| 数据隔离 | MyBatis-Plus 拦截器自动注入 `user_id` |
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
- AgentScope workspace 在本地文件系统（`~/agentscope-workspace/`）

前端：
- Vite dev server (localhost:5173)
- 代理到后端 localhost:8080
```

### 10.2 生产部署（未来）

```text
Kubernetes:
- Spring Boot 打包为 Docker 镜像
- AgentScope workspace 挂 PVC
- Session 后端切到 Redis / PostgreSQL
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
| `RuntimeContext` | 绑定 `userId` / `sessionId` / workspace |
| `StateModule` | 可序列化状态，支持 checkpoint |
| `WorkspaceSession` | Session 后端，存 StateModule 快照 |
| `SessionTree` | JSONL 文件管理，`*.log.jsonl` 永不压缩 |
| `Memory` | 双层：日志(`memory/YYYY-MM-DD.md`) + 长期(`MEMORY.md`) |
| `Compaction` | 结构化压缩，保留目标/状态/发现/计划 |
| `Skill` | Markdown 模板，成功模式自动保存 |
| `agent_spawn` | 委派 subagent |
| `ToolCallback` | 工具定义接口 |
| `NamespaceFactory` | 多租户路径隔离 |

### 12.2 参考资料

- AgentScope Java 官方文档：https://java.agentscope.io/v2/en/
- AgentScope GitHub：https://github.com/agentscope-ai/agentscope-java
- Elasticsearch Java API Client：https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html
- Spring Boot 3.5 文档：https://docs.spring.io/spring-boot/docs/3.5.x/reference/html/
- MyBatis-Plus 文档：https://baomidou.com/

---

**文档结束**
