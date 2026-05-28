# AGENTS.md

本文件约束后续 Agent 在本项目中的实现方式。实现前先阅读 `docs/PROJECT_DESIGN.md`。

## 1. 项目目标

构建一个面向学习备考场景的 AI 学习助手，支持：

- 学习资料上传、分片上传、断点续传和文件去重。
- 文档解析、文本切分、向量化和 Elasticsearch 索引。
- BM25 + 向量混合检索、父子检索、RRF 融合。
- 基于 Spring AI Alibaba 的状态化学习 Agent。
- SSE 流式输出 token、阶段状态和工具调用事件。
- 复习卡生成、FSRS 复习调度和复习记录。
- 工具鉴权、资源范围控制和调用审计。
- 上下文压缩、持久化快照和会话恢复。

## 2. 架构原则

- 使用轻量 DDD + 模块化单体。
- 不拆微服务，除非用户明确要求。
- 优先保持模块边界清晰，而不是过度抽象。
- 业务模块不能直接依赖具体中间件 SDK，应通过 infrastructure adapter 访问。
- 重要状态必须持久化到 MySQL，Redis 只作为缓存、锁、Bitmap、短期状态使用。
- 不使用静默降级策略。依赖失败时返回明确错误或进入明确失败状态。
- 不添加非必要 try-catch。只在需要转换业务状态、补充上下文或释放资源时捕获异常。
- 作为资深软件工程师，写代码时为代码添加详尽的注释。使用中英文混合或纯中文解释核心逻辑，不仅解释“是什么”，还要解释“为什么”。但避免过于显然的注释。

## 3. 推荐包结构

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
    knowledge
    rag
    learning
    review
    tool
    evaluation
```

每个业务模块内部优先使用：

```text
application
domain
infrastructure
interfaces
```

约定：

- `interfaces` 放 Controller、Request、Response、SSE endpoint。
- `application` 放用例服务、事务编排、DTO 转换。
- `domain` 放领域对象、状态机、领域服务、策略接口。
- `infrastructure` 放 Mapper、Repository 实现、外部服务适配。

## 4. 代码风格

- Java 代码优先使用清晰命名，不用晦涩缩写。
- DTO、Entity、Domain Object 不要混用。
- Controller 不写业务逻辑。
- Mapper 不写复杂业务判断。
- 事务边界放在 application service。
- 配置项必须写入配置类，不要散落字符串。
- 时间字段统一使用 `LocalDateTime` 或项目约定类型。
- 数据库主键类型先统一使用 `Long`，除非用户另行指定。
- JSON 字段统一以 `metadataJson`、`xxxJson` 命名，并在应用层转换成结构化对象。

## 5. 异常与失败处理

- 不要吞异常。
- 不要假装外部服务调用成功。
- 不要自动切到另一个 provider，除非需求明确要求。
- 文档处理失败要写入文档状态和错误信息。
- Agent 工具调用失败要发出 `tool.failed` SSE 事件，并写入工具审计记录。
- RAG 无召回结果时返回明确的“知识库未检索到相关内容”，不要编造答案。
- WebSearch 只能在会话配置允许时使用。

## 6. 数据一致性

- 文件去重使用 MD5/SHA256 + Redisson 分布式锁。
- RocketMQ 消费必须幂等。
- ES 索引写入后要能通过业务表追踪对应 `es_doc_id`。
- 删除文档时必须考虑 MySQL、ES、对象存储的一致性。
- 可以使用最终一致性，但必须有可重试任务或明确状态。
- 不要把 Redis 当作唯一数据源。

## 7. Agent 和工具约束

学习 Agent 初版使用状态化工作流：

```text
PLAN -> RETRIEVE -> TEACH -> QA -> QUIZ -> CARD -> SUMMARY
```

工具要求：

- 每个工具必须有名称、描述、参数对象和权限声明。
- 工具调用前必须校验用户、会话和资源范围。
- 工具调用后必须写入 `tool_call_records`。
- `knowledge_search` 工具只能查当前会话允许的知识库。
- `review_card_write` 工具只能为当前用户写入复习卡。
- `web_search` 工具默认不启用。

## 8. SSE 约束

SSE 接口必须输出结构化事件，推荐事件名：

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

不要只输出纯文本 token。阶段状态和工具状态也要输出，方便前端展示 Agent 正在做什么。

## 9. 上下文压缩约束

- 完整消息存入 `chat_messages`。
- 压缩快照存入 `chat_context_snapshots`。
- 快照必须记录 `covered_message_id`。
- 恢复上下文时加载最近快照，再加载该快照之后的消息。
- Redis 只缓存活跃会话上下文。
- 压缩失败不能删除原始消息。

## 10. 检索约束

RAG 初版至少支持：

- BM25 关键词检索。
- 向量检索。
- RRF 融合。
- 知识库权限过滤。
- 返回引用 chunk 和 document 信息。

父子检索建议实现：

- 子 chunk 用于召回。
- 父 chunk 用于补全文档上下文。

不要在没有依据的情况下生成引用。

## 11. 测试要求

新增核心逻辑时必须补测试。优先覆盖：

- 文件分片上传状态判断。
- 文件去重并发场景。
- 文档状态机。
- RocketMQ 消费幂等。
- RRF 排序。
- RAG 权限过滤。
- 工具鉴权。
- 上下文快照恢复。
- FSRS 调度计算。

对中间件相关逻辑优先写集成测试或使用 Testcontainers。若当前阶段尚未引入 Testcontainers，需要至少保留可执行的服务层测试和清晰的 TODO。

## 12. Docker Compose 约束

开发环境应包含：

- MySQL
- Redis
- Elasticsearch
- Kibana
- RocketMQ namesrv
- RocketMQ broker
- RocketMQ dashboard
- MinIO 或 RustFS

中间件连接参数必须通过配置文件或环境变量注入，不能硬编码到业务类。

## 13. 实现顺序

优先按以下阶段推进：

1. 初始化 Spring Boot 工程、基础配置、docker-compose。
2. 文件上传、分片上传、断点续传、对象存储适配。
3. 文档处理异步链路和状态机。
4. Elasticsearch 索引、embedding、混合检索。
5. 学习会话、SSE 和状态化 Agent。
6. 工具系统、工具鉴权和审计。
7. 复习卡和 FSRS。
8. RAG 评测和 Skill 扩展。

## 14. 禁止事项

- 禁止为了演示效果硬编码答案。
- 禁止在 RAG 无结果时编造知识库来源。
- 禁止把用户 ID、知识库 ID 等权限条件交给模型自行决定。
- 禁止在 Controller 中直接调用 Elasticsearch、Redis、RocketMQ 或对象存储 SDK。
- 禁止新增无意义抽象层。
- 禁止新增大范围 try-catch 包裹业务流程。
- 禁止引入降级 provider 掩盖真实错误。
- 禁止在未说明的情况下删除用户数据或清空索引。
