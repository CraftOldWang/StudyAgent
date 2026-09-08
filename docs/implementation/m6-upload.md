# M6 上传实现与测量

本模块对应目标契约 §6、§7.3。实时状态只维护在根 `PROGRESS.md`。当前先完成旧实现基线，再替换原生 multipart；本文不把设计当成已验收结果。

## 优化前基线

源码基线 `bd80e08569d9154ed31aa013b6db6f9f2426d528`。原 JAR 保存在 `.eval/baselines/m6-before-native-upload/study-agent.jar`，SHA-256 为 `d928275a0624ab282c5ab73081a026afc400910f121fe0538673b2c4c7d1c05a`。

仅增加 VERIFY/MERGE 计时日志后的 JAR 保存在 `.eval/baselines/m6-original-upload-instrumented/study-agent.jar`，SHA-256 为 `ba8fde5fa444072484e1f18a50edb902463787512e3d598e084772c1b4695c07`，同目录保留七行插入的源码补丁与部署覆盖配置。14 项上传定向测试通过，打包成功；测试和构建日志为 `.eval/m6-upload-baseline-test.log`、`.eval/m6-upload-baseline-package.log`。

旧实现把每片作为独立对象保存；完成时读一次所有分片计算完整 SHA-256，再读一次并流式写入最终对象。Bitmap 保存进度，但这两遍读取与合并仍经过应用服务。正式测量前不修改算法。

## 测量口径与隔离

- 固定 400,000,000 字节，8,388,608 字节一片，共 48 片；每种路径分别测 1 和 4 并发，每组五次，奇偶轮反转执行顺序。
- `scripts/benchmark-upload.py` 用固定种子生成本地随机字节。扩展名为 PDF 仅用于进入既有上传接口，这不是解析质量样本，不宣称它是可阅读课程 PDF。
- 每次新建知识库绕开同用户、同库、同哈希秒传。所有组使用相同文件内容和客户端脚本。
- 完整计时从客户端读取文件计算 SHA-256 开始，包含初始化、分片读取和 HTTP 传输、状态确认、服务端合并校验、数据库提交和真实 MQ 入队；不含样本生成和建库，不含异步解析、embedding、索引。
- 使用独立 MySQL `study_agent_upload_eval`、Redis DB 2、RustFS bucket `study-agent-upload-eval`、MQ topic `study-agent-upload-benchmark`。原课程库、来源内容和模型接口不进入本实验。
- 通过 RocketMQ Spring 2.3.5 的 `rocketmq.consumer.listeners.<group>.<topic>=false` 停用测试消费者，保留真实 producer/broker。已查本地依赖字节码，并从启动日志确认 listener 未初始化；不依赖配置猜测。`.eval/upload-benchmark.compose.json` 保存完整覆盖。
- 20 MB 烟测串/并发均成功，数据库有两个 STORED 文档、零 chunk，确认未进入处理流水线。LLM 账本仍为 394 次。
- Windows 本机客户端通过 loopback 到 Docker Desktop，JVM `-Xmx384m`，Docker 可用内存约 3.818 GiB；不将结果外推为弱网或公网性能。保留每次结果和容器资源快照，不仅保留最好结果。

原始数据在 `.eval/runs/m6-upload-original-smoke`、`.eval/runs/m6-upload-original`；汇总脚本 `scripts/summarize-upload-benchmark.py` 关联客户端记录与服务端 sessionId 阶段日志，失败尝试仍计入报告。

正式基线 10/10 成功，详见 [完整数据与统计](../evidence/m6/upload-original-v1.json)。两组每次上传均有 48 片、完整哈希校验和唯一的新测试库，没有秒传。加上烟测共 12 个 COMPLETED 会话、12 个 STORED 文档、零 chunk。

| 原实现组 | 次数 | 总耗时中位数 / 范围 | 分片传输中位数 | 完成接口中位数 | 服务端合并 / 校验中位数 |
|---|---:|---|---:|---:|---:|
| 1 并发 | 5 | 41.103 s / 31.068–45.823 s | 27.661 s | 12.759 s | 9.070 / 2.623 s |
| 4 并发 | 5 | 35.048 s / 23.839–35.818 s | 20.397 s | 13.739 s | 9.286 / 3.400 s |

这是优化前结果，暂不作为原生 multipart 优化收益。服务端阶段中位数不必与完成接口中位数相加相等：完成还含状态查询、元数据提交与入队，而且各列独立求中位数。首次轮次较快、后续变慢的现象保留，不删除“慢样本”。数据只反映当前本机共享资源环境。

## 原生实现约束

以下后端方案已实现；浏览器界面仍待接入：

1. 原生 `CreateMultipartUpload → UploadPart → CompleteMultipartUpload`；MySQL 保存 uploadId、每片编号/ETag，Bitmap 用于进度。数据库中的已成功分片可恢复丢失的 Bitmap。
2. 对象 key 属于上传会话，避免仅凭客户端自报哈希覆盖已有对象。合并后流式读取完整对象验证 SHA-256，通过后才建立可用文件/文档并入队。
3. 合并、校验、发布的阶段结果持久化；重复完成返回原结果，中断后复用已经合并的对象。S3 完成后丢失 HTTP 响应时检查该会话对象，不能盲目创建另一份文件。
4. `(user_id, knowledge_base_id, file_hash)` 唯一约束兜底文件并发去重；返回已有文档，不重复入队。锁覆盖数据库提交，避免现有 `@Transactional` 方法在事务提交前释放锁的问题。
5. 分片写入不更新整份过时会话快照；进度从分片事实聚合。参数不同的文件不能误复用旧上传布局。跨用户/跨库、哈希不匹配、未完成、过期与取消有明确结果。
6. 浏览器 Worker 增量 SHA-256 与上传客户端已编写，界面接入待完成。目标为默认 8 MiB/4 并发，展示字节进度、校验/合并状态及可恢复错误；暂停后查询缺失分片继续，不把前端断开当成服务器取消成功。

S3 官方契约要求非末片至少 5 MiB、part 编号 1–10,000；complete 使用保存的编号和 ETag，ETag 不作为完整 SHA-256。具体 RustFS 行为仍以本地真实验收为准。参考 [multipart 限制](https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html)、[multipart 生命周期](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)。

## 原生后端验收

- 定向 20 项通过，完整后端 207 项中 204 项通过、3 项既有跳过。首轮真实 API 在初始化后读取分片时发现全局租户拦截器自动追加 `user_id`，而 V12 新表缺少该列；失败保留在 `.eval/runs/m6-native-upload-acceptance-v1`。
- V13 为分片表补齐 user_id，并从所属会话回填；不绕过租户隔离。修正后 11 项定向回归/打包通过，V12/V13 在独立库实际迁移成功。
- [真实 API 验收 v2](../evidence/m6/native-upload-acceptance-v2.json)：20 MB 合成样本先存首片 8,388,608 字节，重复分片保持一条 ETag；删除 Redis Bitmap 后从 MySQL 恢复。重启应用，初始化复用同一 session/uploadId，只上传剩余 11,611,392 字节。
- 两个并发 complete 返回相同文件和文档，重复 init 复用同一文档；数据库每类记录各一条。HTTP 发布路径仅一次 MQ 入队。消费者被禁用，因此 STORED 补偿任务仍可能周期性重新入队同一个 documentId；这与重复上传创建第二份处理任务不同，不声称 MQ exactly-once。
- 错误哈希保留 HASH_FAILED 且不产生文档，已合并错误对象可取消，重复取消幂等。未上传完整拒绝完成；不同用户和不同知识库不能操作该会话。
- 本轮模型调用 394→394，零新增 chunk。真实验收不代表解析、课程模型或完整学习流程通过。完整对象校验仍保留；新实现同时减少逐片重复统计和长事务，性能比较不能只归因于单个 S3 调用。

完整源码在 `ingest/upload`，短事务发布为 `UploadPublicationService`；阶段为 INITIALIZING → UPLOADING → MERGING → VERIFYING → VERIFIED → COMPLETED，哈希失败单独标记。合并响应丢失后按会话唯一 key 查对象，已验证阶段重试不重新读文件；上述完成阶段恢复先有单元测试，真实重启验收目前发生在分片上传后。

## 待验收

原生串/并发各五次、暂停续传的实际补传字节、同文件并发完成的唯一文件/文档/MQ 入队、重启后完成恢复、哈希不匹配拒绝、来源权限与界面上传。最终报告必须与优化前数据并列，不预设性能提升。
