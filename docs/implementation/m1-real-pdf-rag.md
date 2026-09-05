# M1 真实 PDF 到 RAG 工具实现记录

- 完成日期：2026-09-05
- 实现基线：`3ba32fa`
- 状态：实现、真实环境验证与独立 verifier review 均已完成

## 纵向链路

- 知识库支持创建、列表、重命名和文档列表；所有操作先校验服务端 user/KB ownership。
- PDF 直传或秒传后保存至 RustFS/MinIO-compatible 对象存储，事务提交后发送 RocketMQ 消息。消费端从对象存储取回 PDF，通过 Tika 解析，生成 PARENT/CHILD 分块，调用官方 `dashscope-sdk-java` 的 DOCUMENT embedding，最后写入 Elasticsearch。
- 检索先为 query 调用 QUERY embedding，在当前 user/KB filter 下执行 BM25 与向量检索，用 RRF 合并并回填父块。结果含 `chunkId`、`content`、`provenance(documentId, documentTitle, sourceLocation)` 和 `score`。
- DashScope SDK 2.22.32 传递的 Okio 3.6.0 与 AgentScope 2.0.1/OkHttp JVM 5.3.2 不兼容，真实运行触发 `Okio.socket` 二进制签名错误；直接锁定 `okio-jvm:3.16.4` 后恢复。`TextEmbedding(String)` 把参数当作 API base 而非 host，根据 SDK 本地字节码中的默认常量，默认配置修正为 `https://dashscope.aliyuncs.com/api/v1`。

## AgentScope 工具边界

- `knowledge_search` 是真实 AgentScope `AgentTool`/`Toolkit` 工具，模型 schema 只暴露 `query`。`userId` 和 `knowledgeBaseId` 由服务端 `RuntimeContext` 中的 `KnowledgeSearchScope` 注入，模型不能选择或改写权限条件。
- `POST /api/knowledge-bases/{id}/agent-search` 使用专用 ReActAgent，其 Toolkit 仅注册 `knowledge_search`。未复用 HarnessAgent，因为后者会自动加入文件系统、命令执行和记忆工具，不符合“只允许当前 user/KB 检索 chunk 进入 DeepSeek”的边界。
- Agent 响应为 `{query, answer, toolInvoked, hits}`。`toolInvoked` 和 `hits` 只从当次真实工具执行的 `RuntimeContext` 结果捕获；多次检索按 `chunkId` 累积去重全部实际出处。没有调用时返回 `false/[]`，无检索证据时返回 `true/[]`；这两种情况都由服务端固定返回明确无依据文本，不信任模型自由文本，也不构造出处。
- `AgentInvocationScopeFactory` 同时注入 `AgentInvocationScope` 与 `KnowledgeSearchScope`，并提供可复用的 `validateKnowledgeBaseScope(userId, knowledgeBaseId)`，M2 可在调用计划模型前先校验会话范围。

## HTTP 契约

- `POST /api/knowledge-bases/{id}/search`：请求 `{ "query": "..." }`，返回 direct retrieval 结果。
- `POST /api/knowledge-bases/{id}/agent-search`：请求 `{ "query": "..." }`，返回 `{query, answer, toolInvoked, hits:[{chunkId, content, provenance, score}]}`。
- 无证据 direct/Agent 响应的 `hits` 均为空，且返回“当前知识库没有可支持该问题的资料依据”。跨用户访问他人 KB 返回 HTTP 400。

## 验证证据

- 使用一页真实 PDF（1753 bytes），唯一标记为 `ORCHID-COMET-7319`。pdfplumber 解析与 Poppler 渲染均验证文件有效。
- 隔离 MySQL 中 document `2096049520543543297` 为 `INDEXED`，有4个 PARENT 和4个 CHILD chunk；file record 为 `STORED`。RustFS 中存在与记录 storage key 同哈希的对象元数据。
- Elasticsearch `chunks-v1-read` 中标记命中4条 PARENT/CHILD 索引文档，均带 `document_title` 和 `source_location`。direct search 返回标记及出处。
- 真实 DeepSeek Agent 调用返回“ORCHID-COMET-7319 证明了索引 PDF 检索”，`toolInvoked=true`，`hits` 是当次 `knowledge_search` 的实际结果。空 KB 的 direct/Agent 检索均返回空 hits 与明确无依据回答。
- Linux Maven/JDK21 全量测试：97 tests，0 failures，0 errors，3 skipped，BUILD SUCCESS。`mvn compile`：109 source files，BUILD SUCCESS。代码与 POM 不含 `io.agentscope.core.rag`、Spring AI 包或依赖。

Windows 宿主上 AgentScope/Elasticsearch 在 JDK HttpClient `Selector.open` 处因本机 loopback pipe 失败，使用 `require_escalated` 后仍复现；Linux JDK21 容器可正常启动并完成上述真实纵向链路，未在代码中添加降级逻辑掩盖环境限制。
