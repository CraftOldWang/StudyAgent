# M0 干净切换实现记录

- 完成日期：2026-09-04
- 基线：`a8756dc`
- 状态：实现者验证与独立 verifier review 均通过

## 切换结果

- 依赖/引用检查后，将仍被 ingest 消费的 Tika 解析、S3 对象存储、分片上传、RocketMQ/Canal 同步与消息契约迁入 `ingest/parse`、`ingest/storage`、`ingest/upload`、`ingest/sync`；`UploadSession` 与 Mapper 分别迁入全局 `model`、`mapper`。
- 删除旧 `modules/`、顶层 `infrastructure/`、旧 Controller/Service/Test、旧 MapperScan 与整个旧 `frontend/`。未删除 `.codex/research-sources`、`.codex/worktrees`、`.claude`、`.agentscope` 等未跟踪内容。
- Phase 3 learning/compaction WIP 以提交 `95af8b1` 保存在 `codex/phase3-wip`，没有纳入 M0 `main`。
- 删除 Spring AI BOM、依赖和 Chat/Tool/Embedding 实现；Agent Runtime 只保留 AgentScope。没有引入已标记移除的 `io.agentscope.core.rag`。
- embedding 使用官方 `com.alibaba:dashscope-sdk-java:2.22.32`，由 `EmbeddingPurpose` 显式映射 DOCUMENT/QUERY。排除 SDK 传递的 `jsonschema-generator:4.31.1`，避免覆盖 AgentScope 需要的 4.38.0。
- 修复 S3 endpoint 的 YAML 占位符解析，并将 MyBatis-Plus 配置恢复到正确顶层。新增 V4 migration 承接分片上传会话，避免改写已发布 migration。
- 初始用户保持 `default-user`；users DDL 显式声明 InnoDB、utf8mb4 与 `utf8mb4_0900_ai_ci`。

`FileUploadService` 迁移后仍超过 300 行。本批只做无兼容层的包切换并保留既有分片上传/Redis/S3 事务编排与测试，不在 M0 同时重构已工作的流程，以免把结构清理扩大为行为改写。

## 验证

- M0 定向测试：17 tests，0 failures，0 errors，2 skipped；两个 migration integration test 仅在显式提供测试库 URL 时运行。
- 隔离 MySQL 上的三组 migration integration test：3 tests，0 failures，0 errors，0 skipped。
- `mvn compile`：成功。
- Windows 全量 `mvn test`：102 tests，0 failures，1 error，3 skipped；唯一 error 是 runner 禁止 JDK selector 的 loopback pipe，`ElasticsearchConfigurationTest` 在 `Selector.open` 前失败。JDK21、JDK23 与非沙箱请求均复现，未修改代码掩盖环境限制。
- Linux Maven/JDK21 容器全量测试：102 tests，0 failures，0 errors，3 skipped，BUILD SUCCESS；其中 `ElasticsearchConfigurationTest` 正常通过。
- `mvn package -DskipTests`：成功生成可执行 jar。
- Docker smoke：MySQL、Redis、Elasticsearch、RocketMQ、RustFS 启动成功；隔离数据库从空库应用 V1-V4；应用成功启动。使用根 `some_apiKey` 中的 DeepSeek key 仅做进程内环境变量映射，调用 `POST /api/agent/hello`（`X-User-Id: 1`）返回真实 DeepSeek 文本 `Hello! How can I help you today?`。

## 后续边界

- M1 再实现知识库管理、完整 PDF→RAG、稳定 `AgentTool` 检索接线和新前端。
- M2 再从学习会话恢复 user/KB scope，并迁移或重写 learning/compaction WIP。
