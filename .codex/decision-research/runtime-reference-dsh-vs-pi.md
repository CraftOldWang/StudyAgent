# Runtime 参照：DeepSeek Harness 与 Pi

> 用途：只记录可以用于 StudyAgent 决策的机制差异，不把任一实现当成必须照搬的架构。

| 能力 | DeepSeek Harness | Pi | 对 StudyAgent 的启示 |
| --- | --- | --- | --- |
| Loop | 应用 driver 拥有 turn/step、取消、事件 | `runLoop` 拥有 assistant/tool/follow-up | 应用拥有 Loop 是两者共同点 |
| Tool batch | parallel/exclusive、bounded pool、顺序 commit | 默认 `Promise.all`，可切 sequential，顺序归并 | 只读并发、写入 barrier 是稳健默认 |
| Session | append-only durable event log + surface | append-only JSONL tree + context projection | Pi 更轻；Harness 更适合恢复/回放 |
| Compaction | surface replacement + durable marker/provenance | summary + retained tail | 压缩应是投影，不删除原始事实 |
| Checkpoint | flush、stable prefix、崩溃关闭 open turn | resume/fork 会话，但不恢复外部副作用 | 业务 checkpoint 仍需 StudyAgent 定义 |
| Subagent | 独立 session、lineage、权限快照、continuation | 无真正任务调度 API | Harness 可作产品 subagent 参照 |
| Prompt | 插件 section/scope、tool order、schema tool | `.md` + frontmatter + diagnostics | Git 资源文件更适合作为初版 |
| Trace/replay | durable events 与 live telemetry 分离 | listener events 不完整持久化 | 业务 event store 不能由 OTel 或 SSE 代替 |

## DeepSeek Harness 关键位置

- Loop：`D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/agent.ts:225`
- Tool scheduling：`D:/1Learningoutput/deepseek-harness/packages/core/agent-loop/src/tool-calls.ts:112`
- Session durability：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/index.ts:1009`
- Event types：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/types.ts:236`
- Subagent continuation：`D:/1Learningoutput/deepseek-harness/packages/subagent/subagent/src/continuation.ts:409`

## Pi 关键位置

- Loop：`D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:170`
- Tool parallelism：`D:/1Learningoutput/javabackend/pi/packages/agent/src/agent-loop.ts:535`
- Session tree：`D:/1Learningoutput/javabackend/pi/packages/coding-agent/src/core/session-manager.ts:845`
- Context rebuild：`D:/1Learningoutput/javabackend/pi/packages/coding-agent/src/core/session-manager.ts:1255`
- Compaction：`D:/1Learningoutput/javabackend/pi/packages/agent/src/harness/compaction/compaction.ts:639`
