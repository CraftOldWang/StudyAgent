---
status: accepted
---

# 分阶段切换 ingest V2 模型与 pipeline

采用临时双模型方案：1.12a 只新增严格对应 V2 schema 的全局 `model/mapper`，不修改旧消费者，也不删除旧六个实体/Mapper；#19 使用新模型实现并切换最小 `DocumentPipeline` 后，再迁移仍需保留的直接消费者并删除旧六类型。1.12a 与 #19 属于同一验收批次，中间状态不可部署，也不能把 1.12a 单独标记为最终完成；这样既不向干净 V2 塞回 legacy 字段，也不把 package 对齐扩大成全链业务重写。

## Considered Options

- 扩充 V2 保留 legacy 字段：旧消费者可继续运行，但会同时保留两套哈希、存储、状态和索引同步字段，需要双写与一致性规则，违背严格 MVP。
- 临时保留新旧两套模型并同批切换：存在短期重复类型，但不改变 V2，且业务切换集中在拥有 pipeline 语义的 #19，因此采用此方案。
- 扩大 1.12a 或 #19，一次重写/删除上传、性能、补偿、评测等旧链：没有中间态，但任务范围和验收面显著扩大，也可能误删当前设计要求复用的 ingest 工程件。

## Consequences

- 1.12a 只创建 `com.studyagent.model` 与 `com.studyagent.mapper` 下的三实体、三 Mapper；legacy 类型和消费者在 #19 切换前保持原样。
- #19 完成新 pipeline 接线并迁移必要消费者后，删除旧六类型；上传、S3、MQ、Canal 等仍在 V1 范围内的工程能力不得借清理之名删除。
- 1.12a 与 #19 之间允许编译期共存，但不做运行验收、不部署；完整批次通过后才能关闭两个任务。

跟踪任务：[Issue #20](https://github.com/CraftOldWang/StudyAgent/issues/20)、[Issue #19](https://github.com/CraftOldWang/StudyAgent/issues/19)。
