---
status: accepted
---

# Phase 1 使用确定性 token 计量与带来源坐标的分块契约

Phase 1 的 `StructuredChunker` 与 `TokenWindowChunker` 统一使用 `com.knuddels:jtokkit:1.1.0` 和 `EncodingType.CL100K_BASE` 实现本地确定性 `TokenCounter`，并以 `ChunkSegment(content, tokenCount, sourceLocation)` 作为后续 pipeline 的稳定内部契约；`SourceLocation` 至少保存解析器输出文本中的 `[startOffset, endOffset)` 与 `headingPath`。这样 900/120 等窗口参数、`ChunkSegment.tokenCount` 和测试使用同一口径，同时结构分块进入 token fallback 后仍能保留来源坐标。该计数值是项目稳定、可复现的分块口径，不宣称等于 DeepSeek 或其他模型供应商的计费 token；计量算法、jtokkit 版本或 encoding 变化都必须提升 `chunker_version`，重新分块、重新索引并运行检索回归。

## Considered Options

- 字符窗口并继续返回 `List<String>`：实现最少，但 900/120 不再是 token 语义，来源坐标和已计算的 token 数也无法随 segment 进入 pipeline。
- 调用模型供应商取得 usage：能反映供应商实际计量，但结果只能在请求后取得，并引入网络和 provider 耦合，不能作为预切分边界。
- `jtokkit:1.1.0` + `CL100K_BASE` 的本地确定性 `TokenCounter` 与 `ChunkSegment` / `SourceLocation`：增加一个明确的计量依赖和内部数据契约，但分块可离线复现、测试稳定，且 provenance 不会在两级 chunker 之间丢失，因此采用此方案。

## Consequences

- 两个 chunker 必须复用基于 `jtokkit:1.1.0`、`CL100K_BASE` 的同一 `TokenCounter`，不得各自实现字符估算或第二套 token 口径。
- `SourceLocation` 的 offset 以解析器输出文本为坐标系；Phase 1 不提前加入 ASR 时间戳等尚未使用的字段。
- `tokenCount` 只属于 `ChunkSegment` 内部分块契约；V2 `document_chunks` 不新增 `token_count` 列。持久化与 ES 装配从 segment 读取内容和来源坐标，不再从 `String` 反向猜测位置。

跟踪任务：[Issue #17](https://github.com/CraftOldWang/StudyAgent/issues/17)、[Issue #18](https://github.com/CraftOldWang/StudyAgent/issues/18)。
