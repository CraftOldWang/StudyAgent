# D-Q02：Spring AI 是否保留

> 状态：READY_FOR_DECISION  
> 日期：2026-09-01

## 决策本质

这里不是简单的“保留或删除依赖”。需要分别决定：

1. Spring AI 是否继续承担 provider、流式响应、tool schema、structured output 和基础 observation 适配；
2. Spring AI 是否拥有 Agent Loop；
3. 使用 1.1 维护线，还是连同 Spring Boot 一起迁移到 2.0 线。

D-009 已经要求 `agent` 拥有 Loop、Context、Tool Governance、Checkpoint、Trace 与 Codec。因此，即使保留 Spring AI，也不能默认把它当完整 Runtime。

## 当前外部事实

- Spring AI 当前官方稳定线包括 2.0.1 与 1.1.8；项目当前是 1.1.0。
- Spring AI 1.1 默认在各 `ChatModel` 内部执行工具循环；设置 `internalToolExecutionEnabled=false` 后，可由应用处理 tool call。
- Spring AI 2.0 把默认工具循环移到 `ChatClient` 的 `ToolCallingAdvisor`；直接调用低层 `ChatModel` 时不会自动执行工具。
- Spring AI 2.0.x 官方对应 Spring Boot 4.0.x/4.1.x，因此升级不是单独更换 BOM。
- Spring AI 提供 provider abstraction、`ToolCallback`、JSON Schema、structured output、Micrometer observation 与流式模型接口。
- DeepSeek 官方工具调用本来也要求客户端执行工具，再把 tool result 放回消息列表；应用自管 Loop 不违背 provider API。

## 选项

### A. 保留 Spring AI 1.1.x，并继续使用默认循环

实施：把版本至少升级到 1.1 最新维护版，继续使用 `ChatClient` 与框架内部工具执行。

优点：

- 改动最小；现有 provider、tool 与 streaming 代码可直接复用。
- 适合快速恢复当前功能。

缺点：

- 内部工具消息默认对应用不可见。
- 很难把 step、预算、审批、checkpoint、批量工具和 replay 变成 Runtime 的一等状态。
- 与 D-009 的 Runtime 边界冲突最大。

判断：不适合作为目标架构，只能作为短期兼容基线。

### B. 升级 Spring Boot 4 + Spring AI 2.x，以 Advisor 作为 Loop

实施：迁移 Boot 4、starter 和 API；让 `ToolCallingAdvisor` 拥有循环，通过 advisor hook 插入 memory、observation、limit 与定制逻辑。

优点：

- 2.x 的 tool loop、limits、memory ordering 和 observability 比 1.1 更完整。
- 框架标准能力多，自建代码较少。

缺点：

- Boot 3 到 4 是单独的大版本迁移，必须验证 MyBatis-Plus、RocketMQ、Spring AI Alibaba 等兼容性。
- Runtime 语义会分散到 advisor chain；checkpoint、业务 verifier 和 event store 仍需自建。
- 容易把“框架可观察”误当成“业务可恢复”。

判断：如果用户本来就想迁移 Boot 4，可以评估；不应只为 Agent Loop 顺手升级。

### C. 保留 Spring AI 作为低层模型适配器，应用拥有 Runtime

实施：

- 1.1 中关闭内部工具执行，或在 2.x 直接调用低层 `ChatModel`；
- 保留 provider、streaming、`ToolCallback`、schema 和模型级 observation；
- `agent/loop` 自己维护 turn、step、消息、工具调度、终止、预算、checkpoint 和事件。

优点：

- 不重复实现 provider wire protocol、SSE/streaming、tool schema 和通用模型 DTO。
- Runtime 控制权清晰，适合 Trace、Replay、Verifier 与面试讲解。
- provider 更换仍可通过 Spring AI adapter 隔离。

缺点：

- 需要重写现有 `ChatClient` 调用链。
- 必须明确哪些重试属于模型 adapter，哪些属于 Agent Runtime。
- Spring AI message/tool 类型仍会进入 adapter 边界，需要防止渗透到核心状态模型。

判断：与 D-009 最吻合。

### D. 完全删除 Spring AI，直接使用 provider SDK/HTTP

实施：为 DeepSeek/OpenAI/DashScope 分别实现 message、stream、tool call、structured output、错误分类、retry 与 observation adapter。

优点：

- 控制权最高；可以精确使用 provider 特性。
- 不受 Spring AI API 演进影响。

缺点：

- provider wire protocol、流式累积、错误处理、schema 与 SDK 升级全部成为项目维护面。
- 多 provider 行为差异会污染 Runtime。
- 自建内容很多，但大部分不是学习 Agent 的差异化能力。

判断：除非 Spring AI adapter 已经阻塞所需能力，否则收益不足以覆盖维护成本。

### E. 更换为 LangChain4j 等其他 Java Agent 框架

优点：可能获得另一套 agent/tool/RAG abstraction。

缺点：仍然必须回答 Loop、checkpoint、trace 与 tool governance 谁拥有；只是把依赖从一个框架换成另一个框架。现阶段还会增加迁移和面试解释成本。

判断：没有当前证据支持引入。

## 推荐

推荐 C：

> 保留 Spring AI 的低层模型与工具适配能力，关闭框架自动工具循环，由 `agent/loop` 拥有迭代、终止、预算、Context、Checkpoint 和 Trace。

版本建议拆成独立子决策：

- C1：先使用 Spring AI 1.1 最新维护版完成 Runtime 重构，控制迁移变量；
- C2：单独做 Boot 4 + Spring AI 2.x 兼容性 spike，通过后再升级。

不建议把 Boot 大版本、包迁移、数据库重建和 Agent Runtime 重写塞进同一批变更。

## 需要用户拍板

1. 选择 A/B/C/D/E；
2. 如果选 C，是先走 C1，还是直接走 C2；
3. Spring AI 类型是否严格限制在 `integration/llm` adapter 内。

## 一手来源

- [Spring AI Tool Calling 1.1](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
- [Spring AI Tool Calling 2.0](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI Getting Started / Boot compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI DeepSeek Chat](https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html)
- [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)
- [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)
- [OpenAI Java SDK](https://github.com/openai/openai-java)
