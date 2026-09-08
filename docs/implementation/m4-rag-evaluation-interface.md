# M4 生产 RAG 评测入口

## 请求与返回

`POST /api/knowledge-bases/{knowledgeBaseId}/search` 使用已有服务端用户 scope 和知识库归属校验。请求示例：

```json
{"query":"什么是稳定匹配？","mode":"RRF","topK":10}
```

mode 可选 BM25、VECTOR、RRF、PARENT，省略时默认 PARENT；topK 可选 1–20，省略时使用配置的 6。BM25 不执行 QUERY embedding。BM25 / vector 各自使用配置的候选规模，RRF 使用配置的 rank constant，未再硬编码为 60。

- `rankedChildren`：应用 topK 后的原始子块排名，保留子块正文、来源坐标、父块 ID、分数和策略。检索 Hit@5 / MRR@10 应使用此字段。
- `hits`：预算内的资料正文。单路/RRF 为子块，PARENT 为去重后的父块；父块使用自身的来源坐标，不再冒用子块坐标。
- `contextMatches`：每份入选正文对应的实际命中子块 ID、子块来源和分数。
- `contextTokens`：入选正文使用 jtokkit / CL100K_BASE 计数的 token 总和，不包括 JSON、查询和来源元数据开销。

PARENT 和 RRF 使用相同的子块排名与 topK 截断，再处理父块回填，避免把父块扩展收益混算为子块排名提升。父块以第一次命中的顺序去重；索引中缺失已引用 parent 时明确报错。

统一正文预算为 4096，所有模式相同。当前保留完整块：依次接受能放入剩余预算的块，过大的块跳过，后续较小块仍可入选；不裁掉证据中间内容。此策略可能留下空余预算，正式报告同时记录预算上限和实际正文 token，不声称每种模式使用了完全相等的 token 数。配置要求至少能容纳一个完整 parent。

Agent 工具只序列化 `modelView`，发送入选正文、来源关联和正文 token 数；用于评测的 `rankedChildren` 不重复发送给模型。学习来源校验接受入选上下文及其实际匹配子块的 ID，不接受因预算被排除且未展示的原始候选。

## 验证

30 项相关回归全部通过，覆盖四种模式、独立候选规模、自定义 RRF 常量、父块去重与来源坐标、缺失 parent 错误、正文预算、BM25 零 embedding 调用，以及学习流程的来源校验。见 [定向回归](../evidence/m4/rag-regression-20260909.json)。新 JAR 独立打包、CRC 和启动就绪均通过。

两份真实资料各执行四种模式、topK=10，共 8 次生产 API 请求；RRF/PARENT 的子块 ID 顺序相同，入选上下文 ID 不重复，正文均不超过预算，BM25 trace 下无 embedding 调用。见 [四模式烟测](../evidence/m4/rag-modes-smoke-20260909.json)。

| 资料 | RRF 子块排名数 / 入选正文数 / token | PARENT 子块排名数 / 入选正文数 / token |
|---|---|---|
| 算法 PDF | 6 / 5 / 3388 | 6 / 2 / 3868 |
| 操作系统 PPTX | 10 / 6 / 4004 | 10 / 1 / 2012 |

这些是机制烟测，不是质量或延迟基准。中文稳定匹配问题在当前英文 PDF 上 BM25 返回空；正式问题集需标记语言/词面匹配条件，报告不同问题类型的结果，不能据此单例宣称 RRF 普遍提高召回。

`scripts/probe-rag-modes.py` 接受 JSONL 问题清单，调用生产 API 并保存逐请求原始响应、trace、耗时、问题集哈希和运行版本信息；通过 `--resume` 跳过相同配置下已成功请求。它只检查机制，不替代黄金证据标注或质量评分。正式批量评价仍需资料清单、100 道问题的开发/冻结验证划分、基于文档哈希与原文证据的标签，以及六组以内统一切块选型。
