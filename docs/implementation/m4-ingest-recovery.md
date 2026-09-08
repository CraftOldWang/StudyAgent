# M4 资料处理与恢复

本页记录实现和证据边界；实时阶段状态只维护在 `PROGRESS.md`。

## 统一切块候选

使用 jtokkit 1.1.0 / CL100K_BASE 计量窗口长度。当前候选为结构优先的 child 800/80、parent 2400/0；先打包同一标题路径内的相邻结构块，再按窗口上限和句段边界划分。较小标题与正文一起打包，短 parent 可以只产生一个等长 child。没有可识别标题时按段落打包与窗口切分，不按课程写特殊规则。PDF/PPTX 当前经过 Tika 纯文本解析，不声称已恢复其几何版面标题层级。

完整指纹包含 tokenizer、策略和全部窗口参数，并进入 chunk ID 和 document/ES 元数据。V8 将 `chunker_version` 扩展到 128 字符；实际运行曾触发原 32 字符字段截断，两份原文档在迁移后由 MQ 重投恢复，没有重新上传。

parent 不调用 embedding，ES 中保存无向量 parent；BM25 和向量召回都限定 CHILD。当前候选在真实 PDF 中产生 2 parent / 6 child，在 PPTX 中产生 3 parent / 11 child。对应实际 DOCUMENT 调用分别为 6 次、11 次。见 [结构切块烟测](../evidence/m4/structured-smoke-20260909.json)。这是烟测，不是检索质量或性能提升指标；六组以内的正式选型尚未完成。

## 阶段产物与执行权

- V9 在 document 保存规范文本对象键/哈希、最近成功阶段、尝试次数、执行令牌、租约和索引目标；`embedding_artifacts` 按用户、内容哈希、模型及维度建立唯一键。
- 规范文本写入 RustFS 后提交 PARSED；重试加载并校验哈希。缺失或损坏产物明确失败，不静默重算。解析器版本不匹配时重新解析。
- chunk 和来源关系在短事务中整体替换；相同解析与切块指纹直接读取持久化 chunk。
- 每个 DOCUMENT embedding 成功后立即保存可复用向量。并发同内容的唯一键竞争使用已提交产物。QUERY 不使用该缓存，其调用仍独立计量。缓存命中记录到应用日志，不虚构 provider usage。
- ES bulk 检查数量、ID、状态码与逐项错误。成功项的 `indexed_at` 先提交，失败项保留；重试只发送未确认项。请求级失败无法确认哪些项已生效，因此重发未确认项，依赖确定性 chunk ID 幂等覆盖。变更物理索引、embedding 模型或维度会清除旧索引确认。
- 每次领取使用新的 UUID 执行令牌并递增尝试次数。阶段、chunk 和完成状态更新先校验令牌与有效租约，再在同一短事务内修改。旧令牌不能覆盖新任务状态；默认租约 5 分钟，阶段和每个 embedding 项前后续租。超过租约的慢调用不能提交，随后可由新任务复用其已保存的不可变产物。
- 后台每 30 秒扫描 STORED / 已过期执行并重新入队；扫描仅跨用户读取路由元数据，消费者重新绑定用户 scope。FAILED 由 RocketMQ 的重投或显式 retry 接口处理，扫描器不无限重跑永久失败。
- Canal 仅重新入队，统一由 MQ 消费者领取执行权。`POST /api/documents/{id}/retry` 只接受属于当前用户的 FAILED 文档，不创建新文档。

执行令牌保护数据库状态提交；它不能撤销已经发往外部系统的请求。外部存储使用内容寻址产物，ES 使用确定性 ID；不声称外部调用 exactly-once。

## 当前验证边界

新增测试覆盖产物缓存命中、并发重复写、损坏产物、规范文本和 chunk 复用、ES 部分确认与跳过已确认 parent、旧令牌不能替换 chunk。完整 176 项测试中原实体字段清单有一项失败；补齐 V9 映射后，该类 4 项定向复验通过，最新报告合计 176 tests / 0 failure / 0 error / 3 skipped。组合测试和定向复验退出码分别保留，见 [测试记录](../evidence/m4/recovery-tests-20260909.json)。独立打包成功、JAR CRC 校验通过，见 [构建记录](../evidence/m4/recovery-package-20260909.json)。

真实 ES 故障注入已通过：临时将独立 eval 索引设为禁止写入，PDF 文档 `2097378373776556034` 在 INDEXING 明确失败。恢复原索引设置后，生产 retry API 成功触发第二次执行；规范文本、8 个 chunk 和全部 6 个子块 embedding 复用，DOCUMENT 调用前后均为 6。数据库确认 2 parent / 6 child 全部有索引确认，attempt_count=2，最终 INDEXED 且令牌/租约清空；恢复后生产检索 API 返回稳定匹配资料。见 [真实故障恢复](../evidence/m4/es-failure-recovery-20260909.json)。该烟测证明产物复用，不作为正式耗时提升指标。

`scripts/verify-ingest-recovery.py` 通过生产上传/查询/重试 API 验证，并在 finally 恢复 ES 原写入设置。`scripts/verify-ingest-lease-recovery.py` 专门终止并重启 eval 容器，保留中断调用 usage，支持 `--resume` 继续观察已有文档而不重做上传/终止操作。

真实进程终止恢复已通过：PPTX 文档 `2097379006365679617` 在 EMBEDDING 时终止 eval 容器，数据库留下 CHUNKED 检查点、1 个已完成子块向量和第 1 次执行的租约。重启后等待原 5 分钟租约到期，由后台扫描重新入队；未调用显式 retry API，第 2 次执行最终完成 3 parent / 11 child 索引。已持久化的第 1 个向量复用；共记录 12 次 SDK 尝试，其中 11 次有终态 usage，1 次进程中断后没有终态 usage。后者保留未知，不能将 6470 个已知 provider token 当作包含该中断调用的精确总账单。见 [真实租约恢复](../evidence/m4/lease-recovery-20260909.json)。观察脚本最初未处理重启期间的 HTTP 502，随后修正并只继续观察原文档；未重新上传或再次终止容器。

生产 retry API 同时验证了已索引文档拒绝重试、其他用户不能重试当前文档，均返回 400；见 [权限与状态校验](../evidence/m4/retry-api-guards-20260909.json)。ES 部分项失败后的逐项重试目前有自动化测试；真实故障场景为整个索引禁止写入，不混称真实部分项故障验收。

后续仍需父块去重、相同上下文预算和命中子块证据保留，生产 API 检索模式与批量评测，以及课程问题集和统一参数选型。当前真实返回中 PDF 的 6 条结果只有 2 份不同父块内容，已确认父块重复回填需要修正。
