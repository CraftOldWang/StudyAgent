# AI 学习助手项目设计

## 1. 项目定位

面向学习备考场景的 AI 学习助手，核心目标不是单次问答，而是把“资料整理、知识检索、学习讲解、即时测验、复习卡片、长期记忆”串成闭环。

项目可以在简历中表达为：

> 基于 Spring Boot、Spring AI Alibaba、Elasticsearch、RocketMQ、Redis、MySQL 与 MinIO/RustFS 构建的 AI 学习助手，支持资料上传、文档解析、混合检索问答、状态化学习 Agent、测验生成、复习卡片生成与 FSRS 复习调度，形成从学习到复习的闭环。

## 2. 建议保留和强化的亮点

### 2.1 SSE 流式响应应该加入

建议加入 SSE，并写进简历。

原因：

- AI 问答和 Agent 工作流天然存在等待时间，SSE 可以明显改善用户体验。
- 面试时容易解释：模型 token 流、工具调用阶段事件、最终答案、错误事件都可以通过统一事件流输出。
- 可以体现后端对长连接、异步任务、前端交互体验的理解。

建议表达：

> 基于 SSE 实现 AI 学习过程的流式响应，区分 token 输出、工具调用、阶段状态与错误事件，提升长耗时 Agent 链路的交互体验。

### 2.2 DDD 可以采用，但不要过度

建议采用“轻量 DDD + 模块化单体”。

项目早期不建议拆微服务。模块化单体更适合简历项目，能体现边界设计，又不会被基础设施复杂度拖垮。

推荐边界：

- `user`：用户、鉴权、角色、权限。
- `storage`：文件元信息、分片上传、文件去重、对象存储适配。
- `knowledge`：知识库、文档、文档块、向量化、索引同步。
- `rag`：检索编排、关键词检索、向量检索、父子检索、RRF 融合。
- `learning`：学习会话、Agent 工作流、对话上下文、SSE 输出。
- `review`：复习卡片、FSRS 调度、复习记录。
- `tool`：AI 工具注册、工具权限、工具调用审计。
- `evaluation`：RAGAS 或自定义评测集、检索质量评估、回答质量评估。

### 2.3 不要把“AI 工具”写成随便调用的工具函数

工具调用是这个项目的一个高质量差异点。建议把工具设计成独立治理对象：

- 每个工具有唯一名称、描述、参数 schema、权限要求、可访问资源范围。
- 工具调用前做鉴权，调用后写审计日志。
- RAG 工具只能访问当前用户有权限的知识库。
- 复习卡写入工具必须绑定当前用户和当前学习会话。
- WebSearch 工具默认关闭或需要显式开关，避免知识库问答边界模糊。

### 2.4 上下文压缩要做成可恢复设计

Redis 可以做热上下文，但 MySQL 应该保存可恢复的压缩快照。

建议策略：

- `chat_messages` 保存完整消息，包括 user、assistant、tool、summary。
- `chat_context_snapshots` 保存压缩后的上下文快照。
- 每个快照记录 `covered_message_id`，表示压缩到哪一条消息为止。
- 恢复会话时加载最近快照，再加载该快照之后的增量消息。
- Redis 只缓存当前活跃会话的 prompt context，不作为唯一数据源。

### 2.5 不建议做复杂多知识库权限，先支持多选即可

初版可以允许一个会话绑定一个或多个知识库，但检索时必须显式传入 `knowledge_base_ids`。

这样既能支持多知识库，又不会把权限、索引、召回范围搞得不可控。

### 2.6 SKILL 支持可以后置

“支持 Skill”是一个加分方向，但不要放到第一阶段。

建议后续设计为：

- Skill 是一组可复用的 Prompt 模板、工具声明、工作流节点和知识库绑定策略。
- 用户可在学习会话中选择 Skill，例如“考研政治背诵”“Java 面试训练”“错题复盘”。
- Skill 不直接拥有数据权限，只能声明需要的工具能力，最终权限仍由用户和会话上下文决定。

## 3. 总体架构

```text
Client
  |
  | REST: 文件、知识库、卡片、会话管理
  | SSE: Agent 学习流式输出
  v
Spring Boot Application
  |
  +-- common
  |     +-- config / constant / exception / response / security
  |
  +-- modules
  |     +-- user
  |     +-- storage
  |     +-- knowledge
  |     +-- rag
  |     +-- learning
  |     +-- review
  |     +-- tool
  |     +-- evaluation
  |
  +-- infrastructure
        +-- objectstorage: S3 compatible adapter, MinIO, RustFS
        +-- ai: model provider adapter
        +-- mq: RocketMQ producer/consumer
        +-- search: Elasticsearch adapter
        +-- embedding: embedding provider
        +-- parser: PDF, Word, Markdown, Text parser
```

外部依赖：

- MySQL：业务数据、消息记录、任务状态、审计记录。
- Redis：上传分片 Bitmap、热点上下文缓存、分布式锁、幂等标记。
- RocketMQ：文档处理异步链路、重试、死信。
- Elasticsearch：关键词索引、向量索引、混合检索。
- MinIO/RustFS：对象存储。
- Spring AI Alibaba：模型调用、工具调用、Agent 链路。

## 4. 核心流程

### 4.1 文件上传流程

```text
初始化上传
  -> 根据文件 MD5 查询是否已存在
  -> Redisson lock: file:dedup:{md5}
  -> 已存在则秒传
  -> 不存在则创建 upload_session

分片上传
  -> 上传 chunk 到对象存储临时目录
  -> Redis Bitmap 标记 chunk index
  -> 返回已上传分片状态

完成上传
  -> 校验 Bitmap 是否完整
  -> 合并或 compose 对象
  -> 写入 file_record / document
  -> 发送 RocketMQ 文档处理消息
```

注意点：

- 文件 MD5 只能作为去重依据，不作为安全校验的唯一依据。
- 上传任务需要过期清理。
- 大文件上传不建议把分片落到本机磁盘。

### 4.2 文档处理异步链路

```text
DOCUMENT_UPLOADED
  -> 文档解析
  -> 文本清洗
  -> chunk 切分
  -> embedding 生成
  -> Elasticsearch 索引写入
  -> document 状态更新为 INDEXED
```

建议状态机：

- `UPLOADED`
- `PARSING`
- `PARSED`
- `CHUNKING`
- `EMBEDDING`
- `INDEXING`
- `INDEXED`
- `FAILED`
- `DEAD`

处理原则：

- 每个阶段状态必须可观察。
- 消息消费必须幂等。
- 失败只在明确可重试的基础设施错误上重试。
- 不做静默降级。
- 不添加无意义 try-catch；异常要么向上抛，要么转换为明确业务状态。

### 4.3 RAG 检索流程

```text
用户问题
  -> query rewrite 可选
  -> 权限过滤 knowledge_base_ids
  -> BM25 关键词检索
  -> 向量检索
  -> 父子检索补全文档上下文
  -> RRF 融合排序
  -> rerank 可选
  -> 构造引用片段
  -> 返回给 Agent 或直接生成答案
```

索引建议：

- 子块索引用于精确召回。
- 父块或文档段落用于上下文补全。
- ES 文档必须包含 `user_id`、`knowledge_base_id`、`document_id`、`chunk_id`、`parent_chunk_id`、`content`、`embedding`、`metadata`。

### 4.4 Agent 学习工作流

建议初版采用状态化工作流，不急着做完全开放的 Agent 循环。

```text
START
  -> PLAN: 判断学习目标，生成本轮学习计划
  -> RETRIEVE: 调用 RAG 工具检索资料
  -> TEACH: 讲解知识点
  -> QA: 回答用户追问
  -> QUIZ: 生成即时测验
  -> CARD: 生成或写入复习卡
  -> SUMMARY: 压缩本轮上下文
  -> END
```

推荐设计：

- Agent 节点固定，工具调用可控。
- 每个节点产生结构化事件，通过 SSE 返回给前端。
- 工具调用失败要进入明确失败事件，不能假装成功。
- 对知识库没有结果时，可以提示缺少资料；是否启用 WebSearch 由会话配置决定。

### 4.5 SSE 事件设计

建议事件类型：

- `session.started`
- `stage.started`
- `stage.completed`
- `token.delta`
- `tool.started`
- `tool.completed`
- `tool.failed`
- `quiz.generated`
- `card.generated`
- `context.summary.completed`
- `error`
- `done`

示例：

```json
{
  "event": "tool.completed",
  "data": {
    "toolName": "knowledge_search",
    "traceId": "xxx",
    "hitCount": 8
  }
}
```

## 5. 建议数据库表

### 5.1 用户与权限

`users`

- `id`
- `username`
- `password_hash`
- `status`
- `created_at`
- `updated_at`

`roles`

- `id`
- `code`
- `name`

`user_roles`

- `user_id`
- `role_id`

### 5.2 文件与知识库

`file_records`

- `id`
- `user_id`
- `md5`
- `sha256`
- `bucket`
- `object_key`
- `filename`
- `content_type`
- `size`
- `storage_provider`
- `status`
- `created_at`

`upload_sessions`

- `id`
- `user_id`
- `file_md5`
- `filename`
- `chunk_size`
- `total_chunks`
- `uploaded_chunks`
- `status`
- `expires_at`
- `created_at`
- `updated_at`

`knowledge_bases`

- `id`
- `user_id`
- `name`
- `description`
- `status`
- `created_at`
- `updated_at`

`documents`

- `id`
- `user_id`
- `knowledge_base_id`
- `file_id`
- `title`
- `source_type`
- `parse_status`
- `index_status`
- `error_message`
- `created_at`
- `updated_at`

`document_chunks`

- `id`
- `document_id`
- `knowledge_base_id`
- `parent_chunk_id`
- `chunk_index`
- `content`
- `token_count`
- `metadata_json`
- `es_doc_id`
- `created_at`

### 5.3 会话与上下文

`chat_sessions`

- `id`
- `user_id`
- `title`
- `mode`
- `status`
- `knowledge_base_scope_json`
- `web_search_enabled`
- `created_at`
- `updated_at`

`chat_messages`

- `id`
- `session_id`
- `user_id`
- `role`
- `message_type`
- `content`
- `tool_name`
- `tool_call_id`
- `metadata_json`
- `created_at`

`chat_context_snapshots`

- `id`
- `session_id`
- `covered_message_id`
- `summary_content`
- `token_count`
- `created_at`

### 5.4 Agent 与工具

`agent_runs`

- `id`
- `session_id`
- `user_id`
- `status`
- `current_stage`
- `started_at`
- `finished_at`
- `error_message`

`agent_step_records`

- `id`
- `agent_run_id`
- `stage`
- `status`
- `input_json`
- `output_json`
- `started_at`
- `finished_at`
- `error_message`

`tool_call_records`

- `id`
- `agent_run_id`
- `session_id`
- `user_id`
- `tool_name`
- `arguments_json`
- `result_summary`
- `status`
- `permission_checked`
- `created_at`
- `finished_at`
- `error_message`

### 5.5 复习系统

`review_cards`

- `id`
- `user_id`
- `knowledge_base_id`
- `document_id`
- `session_id`
- `front`
- `back`
- `tags_json`
- `source_message_id`
- `source_chunk_ids_json`
- `status`
- `due_at`
- `stability`
- `difficulty`
- `elapsed_days`
- `scheduled_days`
- `reps`
- `lapses`
- `created_at`
- `updated_at`

`review_records`

- `id`
- `card_id`
- `user_id`
- `rating`
- `reviewed_at`
- `elapsed_days`
- `scheduled_days_before`
- `scheduled_days_after`
- `stability_before`
- `stability_after`
- `difficulty_before`
- `difficulty_after`

### 5.6 评测

`rag_eval_datasets`

- `id`
- `name`
- `description`
- `created_at`

`rag_eval_cases`

- `id`
- `dataset_id`
- `question`
- `expected_answer`
- `expected_chunk_ids_json`
- `metadata_json`

`rag_eval_runs`

- `id`
- `dataset_id`
- `retriever_version`
- `model_name`
- `status`
- `metrics_json`
- `created_at`

## 6. 包结构建议

```text
src/main/java/.../studyagent
  common
    config
    constant
    exception
    response
    security
  infrastructure
    ai
    embedding
    objectstorage
    parser
    mq
    redis
    search
  modules
    user
    storage
      application
      domain
      infrastructure
      interfaces
    knowledge
      application
      domain
      infrastructure
      interfaces
    rag
      application
      domain
      infrastructure
    learning
      application
      domain
      infrastructure
      interfaces
    review
      application
      domain
      infrastructure
      interfaces
    tool
      application
      domain
      infrastructure
    evaluation
```

每个模块建议：

- `interfaces`：Controller、Request、Response、SSE endpoint。
- `application`：用例编排、事务边界、DTO。
- `domain`：领域对象、领域服务、状态机、策略接口。
- `infrastructure`：Mapper、Repository 实现、外部服务适配。

## 7. Docker Compose 服务

需要启动：

- MySQL
- Redis
- Elasticsearch
- Kibana
- RocketMQ namesrv
- RocketMQ broker
- RocketMQ dashboard
- MinIO 或 RustFS

建议：

- 所有服务放在同一个 docker network。
- RocketMQ broker 显式配置可访问 IP 或容器名，避免 dashboard 和应用连不上。
- Elasticsearch 开发环境可单节点启动，关闭安全认证或明确配置账号密码。
- MinIO 和 RustFS 通过同一套 S3 adapter 访问，业务代码不感知具体实现。

## 8. 测试规划

### 8.1 单元测试

- 文件去重逻辑。
- 上传分片 Bitmap 判断。
- 文档状态机流转。
- RRF 融合排序。
- 工具权限判断。
- FSRS 调度计算。
- 上下文快照恢复逻辑。

### 8.2 集成测试

- MinIO/RustFS 对象存储上传。
- Redis Bitmap 与 Redisson 锁。
- RocketMQ 文档处理链路。
- Elasticsearch 混合检索。
- MySQL 事务与状态更新。

### 8.3 端到端功能测试

- 上传文档 -> 解析 -> 索引 -> 问答。
- 学习会话 -> RAG 检索 -> SSE 流式输出。
- 学习结束 -> 生成复习卡 -> 到期复习。
- 工具越权调用被拒绝并写入审计。

### 8.4 RAG 评测

初版不用立刻接入完整 RAGAS，可以先做小型评测集：

- 20 到 50 个问题。
- 每个问题标注预期文档片段。
- 记录召回率、命中排名、回答是否引用正确资料。

之后再补 RAGAS 指标：

- faithfulness
- answer relevancy
- context precision
- context recall

## 9. 开发阶段路线图

### Phase 1：基础工程与文件链路

- Spring Boot 基础项目。
- MySQL、Redis、MinIO、RocketMQ、Elasticsearch docker-compose。
- 文件上传、分片上传、断点续传、MD5 去重。
- 文档表和文件表。

### Phase 2：文档入库与检索

- 文档解析。
- chunk 切分。
- embedding 生成。
- ES 索引写入。
- BM25 + 向量混合检索。
- 父子检索与 RRF 融合。

### Phase 3：学习会话与 Agent

- chat session/message。
- SSE 流式接口。
- 状态化学习 Agent。
- RAG 工具。
- 上下文压缩与恢复。

### Phase 4：复习闭环

- 复习卡生成工具。
- review_cards。
- FSRS 调度。
- 复习记录。
- 学习后自动生成复习建议。

### Phase 5：治理与评测

- 工具权限控制。
- 工具调用审计。
- RAG 评测集。
- 检索质量指标记录。
- WebSearch 工具可选接入。
- Skill 机制可选接入。

## 10. 简历版本建议

可以改成：

> AI 学习助手（知识库问答 + Agent 学习闭环）
>
> 技术栈：Spring Boot、Spring AI Alibaba、MySQL、Redis、RocketMQ、Elasticsearch、MyBatis-Plus、MinIO/RustFS、Docker
>
> 项目描述：面向学习备考场景设计的 AI 学习助手，支持学习资料上传、文档解析、混合检索问答、状态化学习 Agent、即时测验、复习卡生成与 FSRS 复习调度，形成从资料学习到长期复习的闭环。
>
> 核心工作：
>
> - 使用 MinIO/RustFS 兼容 S3 对象存储保存学习资料，基于 Redis Bitmap 实现分片上传与断点续传，并通过 MD5 哈希和 Redisson 分布式锁解决大文件秒传与并发判重问题。
> - 使用 RocketMQ 构建文档处理异步链路，将文件上传、文档解析、文本切分、向量化和索引写入解耦，结合文档状态机、幂等消费、重试和死信机制提升任务可靠性。
> - 基于 Elasticsearch 实现 BM25 与向量混合检索，采用父子检索补全文档上下文，并通过 RRF 融合多路召回结果，提升知识库问答的相关性和上下文完整性。
> - 基于 Spring AI Alibaba 设计状态化学习 Agent，编排计划生成、知识讲解、答疑、即时测验、复习卡生成等节点，并通过 SSE 输出 token、工具调用和阶段状态。
> - 使用 Redis 缓存活跃会话上下文，并在 MySQL 中持久化上下文压缩快照，实现长对话恢复和 token 成本控制。
> - 设计 RAG 检索、复习卡写入等 AI 工具，统一进行工具鉴权、资源范围控制和调用审计，避免 Agent 越权访问或修改用户数据。
