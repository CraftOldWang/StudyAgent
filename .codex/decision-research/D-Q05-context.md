# D-Q05：Context 管理与压缩

> 状态：READY_FOR_DECISION  
> 前置：D-Q03；与 D-Q07、D-Q10、D-Q11 联合设计

## 必须先区分的对象

- `History/Event`：完整事实，不因 token 预算删除。
- `Message`：满足 provider 协议的 user/assistant/tool 消息。
- `ContextSnapshot`：某次模型调用实际看见的有序消息和版本信息。
- `Working Context`：Context Compiler 选择出的当前输入。
- `Compaction`：用摘要/替代节点减少模型可见历史，不修改原始事实。
- `Learner Memory`：跨会话画像或记忆，不等于 transcript 摘要。

Spring AI 官方也明确区分完整聊天历史与 `ChatMemory`：后者只负责选出发送给模型的相关消息，不适合承担完整历史事实源。

## 方案

### A. Message Window

持久化消息列表，每次按最近 N 条或最近 N token 截取。

优点：简单、行为可预测。缺点：长期目标、未完成动作和旧工具证据可能被窗口挤掉；只按消息数不能处理不同大小消息。

### B. 分层 Context Compiler

从以下来源按优先级和 token 预算编译：

```text
system/policy
current goal + active plan/todo
pending actions/tool obligations
latest user turn
recent raw messages
validated session summary
learner memory candidates/current profile
RAG/tool evidence
```

优点：可解释、可测试、能针对学习任务保留关键状态。缺点：需要定义来源优先级、token estimator、冲突与截断策略。

### C. Event Surface + Context Compiler

Event log 保存事实；surface projection 把原始消息、工具结果和 compaction replacement 投影成模型历史；Context Compiler 再按预算组合其他来源。

优点：最适合 trace、replay、fork、checkpoint 和长期演进。缺点：实现与测试成本最高，需要事件 schema、projection 和 compaction provenance。

## Compaction 触发方案

1. 仅阈值触发：预计下次请求超过预算时压缩。
2. 仅业务边界触发：知识点完成后压缩。
3. 双触发：token pressure 是安全底线；知识点/turn 完成是质量较好的自然切点。

推荐 3。知识点完成不能保证 context 足够小，单纯阈值又可能在语义中间截断。

压缩结果应至少分区保存：

- 已确认目标与计划状态；
- 已完成/未完成动作；
- 用户问题与纠正；
- 工具/知识证据引用；
- 仅作为候选的学习画像信息；
- summary 覆盖的 event/message 范围、prompt/model/version。

## 推荐

推荐 C 的简化首版：

- append-only event/history 保留事实；
- 明确的 message list 满足 provider 协议；
- Context Compiler 按 token budget 编译；
- compaction 产生带 covered range 与 provenance 的替代节点；
- 每次模型调用保存 ContextSnapshot 的引用、token 估算和内容 hash。

不要求首版就支持任意分支和所有来源，但数据模型不应再次把历史拍成一个无界字符串。

## 需要用户拍板

- A/B/C；
- token 预算按 provider tokenizer 精确计算，还是首版允许估算并保留安全余量；
- compaction 是否采用双触发；
- ContextSnapshot 保存完整 payload，还是 payload 放对象存储、数据库只存 hash/引用；
- summary 失败后是保留原上下文并拒绝继续，还是裁剪低优先级工具结果后重试一次。

## 一手来源与实现参照

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Tool Loop and Memory](https://docs.spring.io/spring-ai/reference/api/tools.html)
- Pi context boundary：`D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:320`
- Pi compaction：`D:/1Learningoutput/javabackend/pi/packages/agent/src/harness/compaction/compaction.ts:639`
- DeepSeek Harness compaction：`D:/1Learningoutput/deepseek-harness/packages/compaction/compaction/src/index.ts:88`
