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

### 文件去重预检

```http
GET /api/files/dedup?md5=<file-md5>&sha256=<file-sha256>
```

返回 `duplicated=true` 时，前端可以直接提示秒传命中。

### 初始化分片上传

```http
POST /api/files/multipart/init
Content-Type: multipart/form-data

knowledgeBaseId=<id>
filename=<filename>
contentType=<content-type>
md5=<file-md5>
sha256=<file-sha256>
fileSize=<bytes>
chunkSize=<bytes>
totalChunks=<count>
```

若同一用户、知识库和 MD5 已存在未过期的 `UPLOADING` 会话，会直接返回旧的 `uploadSessionId` 和已上传分片数量，用于断点续传。

### 上传分片

```http
POST /api/files/multipart/{uploadSessionId}/chunks/{chunkIndex}
Content-Type: multipart/form-data

chunk=<chunk-file>
```

`chunkIndex` 从 0 开始。

### 查询分片上传状态

```http
GET /api/files/multipart/{uploadSessionId}
```

返回 Redis Bitmap 还原出的 `uploadedChunkIndexes` 和 `missingChunkIndexes`，前端可据此续传缺失分片。

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

- 分片完成时会顺序读取 RustFS 临时分片并以流式方式写入最终对象，避免把完整大文件一次性放入 JVM 内存；生产化可继续演进为 S3 multipart upload 或服务端 compose。
- 用户体系仍使用默认用户 `1` 做本地验证，后续接入登录后需要把上传会话和文件记录绑定真实用户。
