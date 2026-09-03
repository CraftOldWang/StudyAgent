---
status: accepted
---

# ingest V2 清洁切换契约

在 ADR-0004 的阶段性模型切换完成后，ingest 收敛为一套 V2 上传、文档响应和索引契约：文件哈希统一为 SHA-256，文档接口只暴露 V2 状态，旧上传性能实验链退出产品范围。

## Decision

- `file_records.file_hash` 是完整文件字节的 64 位小写 SHA-256。上传预检在对象写入前以已验证的 `user_id + knowledge_base_id + file_hash` 去重；直传和分片完成时复核最终字节后再落库，不保留 MD5 双轨契约。
- V2 `DocumentResponse` 固定为 `id`、`knowledgeBaseId`、`fileRecordId`、`title`、`contentType`、`pipelineStatus`、`errorMessage`、`createdAt`、`updatedAt`；移除 `fileId`、`sourceType`、`parseStatus`、`indexStatus`。
- 1.12a 与 #19 的切换批次迁移所有必要消费者后，删除历史 `FileUploadPerformanceController`、`FileUploadPerformanceService` 和 `FileUploadPerformanceIntegrationTest`；正常上传、分片续传、pipeline 和补偿能力不随实验链删除。
- Elasticsearch 文档 `_id` 使用业务 `chunk_id`，失败重试以相同 ID 覆盖写。评测从 `document_chunks.document_id` 关联 V2 `documents`，由 `Document` 取得 `user_id` 与 `knowledge_base_id`；不向 `document_chunks` 恢复这两个冗余列。

## Considered Options

- 保留 MD5 + SHA-256 双轨：兼容旧上传代码，但会保留两套去重语义和锁键，不能证明 V2 的唯一哈希契约，因此不采用。
- 把去重推迟到对象写入或 pipeline：实现简单，但重复对象已经产生，且上传前无法提供稳定的秒传结果，因此不采用。
- 继续保留性能实验接口和旧响应字段：短期少迁移几个消费者，但会把非产品接口和两套状态模型带入 V2，因此不采用。

## Consequences

- clean cutover 是破坏性 API/模型切换；旧响应字段、旧状态消费者和性能实验测试必须在同一批次迁移或删除，过渡中间态不可部署。
- ES 的 `user_id` / `knowledge_base_id` 仍可作为 ADR-0003 规定的查询侧隔离字段；它们是索引读点的刻意冗余，不改变 MySQL `document_chunks → documents` 的归属关系。
- 黄金集和评测 fixture 只保存 `chunk_id`，缺少对应 `Document` 或跨 scope 的记录必须拒绝，不能从 chunk 行补造权限字段。

跟踪任务：Issue #19、Issue #20。
