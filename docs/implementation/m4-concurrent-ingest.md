# M4 并发切块与文本格式验收

真实 TXT 与 Markdown 并发上传暴露了 CHUNKING 死锁。两个不同新文档的事务先执行 `DELETE WHERE document_id = ?`，在 `idx_document` 的同一 supremum 间隙持锁，随后互等 INSERT intention；InnoDB 选择回滚 Markdown 事务。原任务经 MQ 重投于 attempt 2 恢复并完成索引，该恢复发生在修正部署前。

修正保留 document 行的执行令牌和租约校验锁，在同一短事务中先普通查询旧 chunk 主键，仅按存在的主键删除，再插入新块。空文档不执行范围删除，同文档写入仍由 document 行锁串行化。没有更改全库隔离级别，也没有吞掉死锁。间隙锁可共存但阻止插入的机制见 [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)。

11 项相关 Java 回归通过。部署后通过实际上传/MQ/embedding/ES 路径运行两轮、每轮四个并发文档，8/8 INDEXED 且数据库 attempt_count 均为 1，InnoDB lock_deadlocks 从 1 保持为 1。该小样本验证修正路径，不代表任意并发压力下零死锁。

独立格式请求同时检索到真实课程衍生 TXT 与原始《算法总结.md》，Markdown 的来源包含 headingPath。6 字节空白文件明确失败于 PARSING，未生成有效索引；现有 MQ 策略仍会有限重试该解析失败，不声称永久失败分类已实现。此前 PDF/PPTX 的 18 份真实资料和故障恢复见 M4 其它报告。

复现入口：`scripts/verify-concurrent-ingest.py --run-dir <独立目录>`；脚本保存上传、轮次和状态响应，数据库首轮成功及死锁计数需另行核对。机器可读证据为 `docs/evidence/m4/concurrent-ingest-v1.json`，原始 InnoDB 报告、HTTP 记录与构建日志保留在其列出的 `.eval/` 路径。
