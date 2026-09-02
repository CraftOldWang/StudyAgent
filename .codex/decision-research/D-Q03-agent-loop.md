# D-Q03：Agent Loop 由谁拥有

> 状态：READY_FOR_DECISION  
> 前置：D-Q02

## 选项

### A. 框架拥有 Loop

Spring AI `ChatClient`/`ToolCallingAdvisor` 反复调用模型和工具，应用只提供 tools 与外围 hook。

优点：实现量少，框架已有 tool schema、memory/observation 组合和调用次数限制。

缺点：业务 turn/step、终止原因、checkpoint、review/verifier 状态不能天然成为 Loop 的控制条件；框架 history 与业务 event store 容易分裂。

### B. 应用完全拥有 Loop

`agent/loop` 每次只调用一次模型；读取 tool calls 后执行工具、追加消息，再决定继续、等待用户、进入 review 或失败。

优点：状态、预算、取消、checkpoint、trace、replay 与 verifier 都有单一控制点。

缺点：需要自己处理合法消息序列、工具并发、部分失败、流式 assistant、模型错误和终止保护。

### C. 混合式

Spring AI 提供模型适配、tool schema、`ToolCallingManager`；应用通过 custom advisor/hook 控制部分策略。

优点：可渐进迁移，复用框架执行能力。

缺点：Loop 状态可能散落在 advisor 与业务 Runtime 两处。只要 checkpoint 或 verifier 需要跨框架内部轮次恢复，边界就会变得含糊。

## 两个本地实现的证据

### DeepSeek Harness

- 应用 driver 拥有 turn/step、inbox、取消、模型请求与事件写入。
- 每个 step 是一次模型调用及其工具；一个 turn 可包含多个 step。
- 每次请求从 session log 重建模型消息。

证据：

- `D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/agent.ts:225`
- `D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/agent.ts:263`
- `D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/agent.ts:339`

### Pi

- `runLoop` 明确负责 assistant、tool calls、tool results、steering/follow-up 与终止。
- provider 调用只在 `streamFunction` 边界。

证据：

- `D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:100`
- `D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:170`
- `D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:320`

## 推荐

如果 Runtime 的面试主线是“可控、可恢复、可评测”，推荐 B；Spring AI 只做模型/tool schema adapter。

最小 Loop 应显式拥有：

```text
load/compile context
-> model call
-> persist assistant/tool requests
-> policy admission
-> execute/commit tool outcomes
-> decide continue | wait_user | review | fail
-> checkpoint/event flush
```

模型不得直接把 Run 标为完成；成功执行进入 `review`，由确定性条件或 verifier 决定 acceptance。

## 需要用户拍板

- A/B/C；
- 一次 HTTP 请求最多运行到哪里：一个 step、一次 turn，还是运行到 `wait_user/review/completed`；
- Writer 是否仍是独立模型调用，还是最终 assistant response 只是 Loop 的最后一步。

## 一手来源

- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)
- [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)
