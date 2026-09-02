# D-Q04：工具治理的具体形态

> 状态：READY_FOR_DECISION  
> 前置：D-Q02、D-Q03

## 决策维度

不要只决定几个数字。工具治理至少包含：描述契约、准入、并发、结果预算、配额、超时、重试、幂等、取消、审计和部分失败。

## Tool Descriptor 方案

### A. 只使用框架 ToolCallback 元数据

只有 name、description、JSON Schema、returnDirect 等字段。

优点：简单。缺点：无法表达副作用、并发与恢复策略。

### B. StudyAgent 自有 descriptor，编译成 Spring AI ToolCallback

```text
name / version / inputSchema
effect: READ_ONLY | WRITE | EXTERNAL_SIDE_EFFECT
concurrency: PARALLEL_SAFE | EXCLUSIVE
timeout
retryPolicy
resultBudget: maxBytes / maxTokens / maxItems
idempotency requirement
required scopes
```

优点：治理语义不依赖 provider 或 Spring AI。缺点：需要 adapter 和一致性测试。

推荐 B。

## 并发方案

1. 全串行：最容易证明，延迟最高。
2. `READ_ONLY && PARALLEL_SAFE` 并发，WRITE/SIDE_EFFECT 形成顺序屏障。
3. 工具自由声明并发级别，Runtime 只执行声明。

推荐 2 作为 fail-closed 默认：未声明、分类失败或策略异常都按 EXCLUSIVE。

DeepSeek Harness 采用 bounded rolling pool、exclusive barrier、并发 dispatch、按模型调用顺序 commit；取消时不再启动新调用，并为未启动调用写显式 aborted result。

本地证据：

- `D:/1Learningoutput/deepseek-harness/packages/core/tools/src/index.ts:1269`
- `D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/tool-calls.ts:112`
- `D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/tool-calls.ts:198`

Pi 默认并行，支持工具或全局切到 sequential，并按原调用顺序归并结果：

- `D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:456`
- `D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:535`

## Result Budget

建议三个独立限制：

```text
maxBytes   防止存储、网络和序列化失控
maxTokens  控制送入模型的上下文成本
maxItems   约束批量写入/返回条数
```

超限语义：

- 默认返回明确的 `RESULT_LIMIT_EXCEEDED`；
- 如果某工具允许截断，必须记录 `truncated=true`、原始大小和截断策略；
- 原始 payload 是否另存对象存储，需要在 D-Q11/D-Q17 决定。

## Timeout、Retry、Idempotency

- retry 默认只对 READ_ONLY 或明确幂等操作开放。
- WRITE/SIDE_EFFECT 必须携带稳定 idempotency key。
- timeout 是业务 outcome，不等于“线程一定已经停下”。
- cancellation 只保证不启动新调用；已发出的外部副作用要依赖幂等或补偿。
- 批量工具必须记录每个 call 的独立 outcome，不能只保留整个 batch 的成功/失败。

建议状态：

```text
PLANNED -> ADMITTED -> STARTED
  -> SUCCEEDED | FAILED | CANCELLED | TIMED_OUT
  -> COMMITTED | COMPENSATION_REQUIRED
```

## 项目适配与推荐的首版边界

- 每 turn 最大总 tool calls；每个 tool 单独上限。
- 写卡等写工具同时受 `maxItems` 与业务 quota 约束。
- 只并发安全的只读工具。
- 工具失败默认返回结构化失败给 Runtime；是否允许模型修正参数重试，由 RetryPolicy 决定。
- 所有调用记录 tool version、effect、attempt、结果大小、idempotency key、outcome 和错误码。

具体数值仍需要在固定 Golden Case 上测量后决定，不能在设计阶段凭感觉写死。

## 需要用户拍板

- 并发方案 1/2/3；
- 超限默认 fail 还是允许按工具截断；
- 写工具是否全部要求 idempotency key；
- 工具失败是立即结束 turn，还是作为结构化 tool result 允许模型修正一次；
- 首版是否实现 compensation 状态，还是只记录 `COMPENSATION_REQUIRED` 供人工处理。

## 一手来源

- [Spring AI Tool Calling and limits](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Anthropic Tool Definition](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools)
- [Anthropic Parallel Tool Use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/implement-tool-use)
- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses)
