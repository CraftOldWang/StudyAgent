# StudyAgent 本地运行说明

## 1. 启动中间件

```powershell
docker compose up -d
```

服务端口：

- MySQL: `localhost:3307`
- Redis: `localhost:6380`
- Elasticsearch: `http://localhost:9200`
- Kibana: `http://localhost:5601`
- RocketMQ namesrv: `localhost:9876`
- RocketMQ dashboard: `http://localhost:8088`
- RustFS S3 API: `http://localhost:9000`
- RustFS console: `http://localhost:9001`

RustFS S3 账号：

- access key: `study`
- secret key: `study123456`

## 2. 启动后端

```powershell
mvn spring-boot:run
```

应用端口：

- `http://localhost:8080`

Flyway 会自动创建 MySQL 表。应用启动时会检查并创建 RustFS bucket 和 Elasticsearch chunk 索引。

## 3. 打开验证页面

浏览器访问：

```text
http://localhost:8080
```

页面支持：

- 自动创建/加载默认知识库。
- 上传文件。
- 通过 RocketMQ 异步解析、切块、向量化、写入 MySQL 与 Elasticsearch。
- 调用 RAG chat 接口并显示引用 chunk。

上传后等待 3 到 10 秒再提问，避免文档还在异步入库。

## 4. 常用接口

### 默认知识库

```http
GET /api/knowledge-bases/default
```

### 普通上传

```http
POST /api/files/upload
Content-Type: multipart/form-data

knowledgeBaseId=<id>
file=<file>
```

### 初始化分片上传

```http
POST /api/files/multipart/init
Content-Type: multipart/form-data

knowledgeBaseId=<id>
filename=<filename>
contentType=<content-type>
md5=<file-md5>
fileSize=<bytes>
chunkSize=<bytes>
totalChunks=<count>
```

### 上传分片

```http
POST /api/files/multipart/{uploadSessionId}/chunks/{chunkIndex}
Content-Type: multipart/form-data

chunk=<chunk-file>
```

`chunkIndex` 从 0 开始。

### 完成分片上传

```http
POST /api/files/multipart/complete
Content-Type: multipart/form-data

uploadSessionId=<id>
knowledgeBaseId=<id>
```

### RAG Chat

```http
POST /api/chat/rag
Content-Type: application/json

{
  "knowledgeBaseId": 1,
  "question": "这份资料主要讲了什么？"
}
```

## 5. 当前实现边界

- 向量化当前使用本地 deterministic hash embedding，方便无模型密钥时完成端到端验证。后续可替换成 Spring AI Alibaba embedding provider。
- RAG 回答当前是检索式摘要，不调用大模型生成。后续接入 LLM 后可以保留引用片段作为上下文。
- 分片合并当前为了 MVP 从 RustFS 读取分片到内存合并，适合本地验证；生产化应改为 S3 multipart upload 或服务端 compose。
- 暂未实现完整 Agent、SSE、工具审计、上下文压缩和 FSRS，这些属于下一阶段。
