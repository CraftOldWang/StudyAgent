# AgentScope Java 2.0.1 API Spike

- 关联任务：[Issue #67](https://github.com/CraftOldWang/StudyAgent/issues/67)
- 检查日期：2026-09-02
- 状态：已完成本机二进制 API 核对，待独立验收
- 范围：只核对本机 Maven 仓库中 AgentScope Java 2.0.1 的公开类、方法与少量路径语义；未修改代码、依赖或配置，未调用远端模型。

## 1. 证据范围

本次核对使用以下 2.0.1 JAR：

- `C:\Users\CraftOldW\.m2\repository\io\agentscope\agentscope-core\2.0.1\agentscope-core-2.0.1.jar`
- `C:\Users\CraftOldW\.m2\repository\io\agentscope\agentscope-harness\2.0.1\agentscope-harness-2.0.1.jar`
- `C:\Users\CraftOldW\.m2\repository\io\agentscope\agentscope-extensions-model-dashscope\2.0.1\agentscope-extensions-model-dashscope-2.0.1.jar`
- `C:\Users\CraftOldW\.m2\repository\io\agentscope\agentscope-extensions-model-openai\2.0.1\agentscope-extensions-model-openai-2.0.1.jar`

使用的核对方式：

```text
jar tf <jar>
javap -classpath <jars> -public <class>
javap -classpath <jars> -c -p <targeted-class>
```

`javap -c -p` 仅用于核对 workspace 路径优先级、字符串路径处理、目录校验和默认 state store 路径，没有把大段反编译内容复制进本文。

## 2. 已证实的 API 与语义

### 2.1 HarnessAgent 与 Spring 集成边界

- `io.agentscope.harness.agent.HarnessAgent.builder()` 返回 `HarnessAgent.Builder`。
- Builder 的最小关键依赖是 `model(io.agentscope.core.model.Model)`；workspace 通过 `workspace(Path)` 或 `workspace(String)` 单独配置。
- Builder 公开 `fallbackModel(Model/String)` 和 `maxRetries(int)`，fallback 是应用显式配置，不会因为 classpath 中同时存在多个 provider 就自动发生。
- `RuntimeContext.builder().userId(...).sessionId(...).build()` 负责调用身份；`RuntimeContext` 不绑定 workspace。
- `HarnessAgent.call(String)` 与带 `RuntimeContext` 的调用入口可用。
- 已安装 JAR 中未发现 Spring Boot 自动配置；0.2 应由应用显式创建 Spring Bean。

### 2.2 Workspace 与 state store

- 未显式配置 workspace 时，路径优先级是 JVM 属性 `agentscope.workspace`、环境变量 `AGENTSCOPE_WORKSPACE`、`${user.dir}/.agentscope/workspace`。
- `workspace(String)` 使用 `Path.of(...)`，不会展开 `~`。因此不能把 `~/agentscope-workspace/` 原样传给 Builder。
- `WorkspaceManager.validate()` 在目录不存在时只告警，不创建目录；应用配置阶段需要显式 `Files.createDirectories(...)`。
- workspace 可以是进程有权限访问的任意路径，并不要求位于仓库或用户目录。
- 默认 `JsonFileAgentStateStore` 使用 `${user.home}/.agentscope/state/<agentId>`。它与 workspace 是两套路径；设置 `.workspace(...)` 不会迁移 state store。
- 已看到的 workspace 文件写入锁是单个 manager/JVM 内的按路径锁；跨进程并发语义尚未由本次静态核对证明。

### 2.3 DashScope、DeepSeek 与 fallback

- DashScope 类为 `DashScopeChatModel` / `DashScopeModelProvider`，`ModelRegistry` 标识为 `dashscope:<model>`，凭据环境变量为 `DASHSCOPE_API_KEY`。
- DeepSeek 位于 OpenAI-compatible 扩展，不是独立 DeepSeek JAR；类为 `OpenAIChatModel`、`DeepSeekModelProvider`、`DeepSeekFormatter`，registry 标识为 `deepseek:<model>`，凭据环境变量为 `DEEPSEEK_API_KEY`。
- DeepSeek provider 默认 base URL 为 `https://api.deepseek.com`。
- 框架提供显式 fallback builder API，但没有框架级自动 provider fallback。是否启用、fallback 模型及 `maxRetries` 均属于应用决策。

### 2.4 能力清单

| 能力 | 已证实的公开 API / 扩展点 | 边界 |
|------|---------------------------|------|
| Loop | `ReActAgent`、`HarnessAgent.call/stream`、`maxIters` | 具体终止与重试策略需运行测试 |
| Context | `RuntimeContext`、`AgentState.getContext()` / `AgentState.contextMutable()` | RuntimeContext 不携带 workspace |
| Compaction | `CompactionConfig`、`CompactionMiddleware` | 可配置压缩，不保证固定保留某组业务字段 |
| Memory | `Memory`、`StateBackedMemory`、Harness `MemoryConfig` | 文件布局和业务记忆策略仍需集成验证 |
| Session state | `AgentState`、`AgentStateStore`、`JsonFileAgentStateStore`、`saveAgentState` | 未发现名为 `StateModule` / `WorkspaceSession` 的公开类 |
| Session log | `SessionTree` | session/transcript/compaction 日志不等同于完整产品 trace |
| Trace | `AgentTraceMiddleware`、`JsonlTraceExporter.builder(Path)` | 前者写 SLF4J；后者才是显式结构化 JSONL exporter |
| Subagent | `SubagentDeclaration`、`AgentSpawnTool.agentSpawn`、`DefaultAgentManager` | parent-child lineage 是否完整进入目标 trace 需运行验证 |

结构化 exporter 记录 `run_id` / `turn_id` / `step_id`；`trace_id` / `span_id` 依赖 OpenTelemetry context。已安装 JAR 中未发现按 `traceId` 查询的现成 API，查询与业务映射需要项目层设计。

## 3. 未证明或当前 JAR 不提供的能力

- 未发现公开类名 `Checkpoint`、`StateModule`、`WorkspaceSession`。checkpoint fork、确定性 replay、崩溃点恢复仍是产品目标，不应写成框架已开箱提供。
- 未证明 `AgentStateStore` 能直接满足第 N 步 checkpoint fork/replay；需要运行 Spike 明确恢复粒度与工具结果处理。
- 未证明 workspace 跨进程并发写入安全。
- 未通过远端调用确认项目最终使用的 DeepSeek model ID。
- 未证明 compaction 会固定保留“目标/状态/发现/计划”；需要以实际 prompt、输入和断言验收压缩质量。
- 已安装的 2.0.1 JAR 只发现 state store SPI、内存实现与 JSON 文件实现，未发现 Redis、MySQL、PostgreSQL 的现成 `AgentStateStore` 实现。
- `AgentTraceMiddleware` 本身不生成可查询 JSONL，`SessionTree` 的 `*.log.jsonl` 也不能直接当成完整 traceId 事件库。

## 4. 对设计文档的修订

本次只修正已经被 2.0.1 JAR 证伪或过度声称的内容：

- 把 `HarnessAgent`、`Model`、`RuntimeContext` 与 workspace 的真实构造边界写清。
- 去掉 literal `~` 路径，补充显式建目录要求，并区分 workspace 与默认 JSON state store。
- 把 DeepSeek 明确为 OpenAI-compatible 扩展 provider；把 fallback 改为显式、非自动。
- 用 `AgentState` / `AgentStateStore` 取代不存在的 `StateModule` / `WorkspaceSession` 名称。
- 将 checkpoint fork/replay 保留为目标，但标记为尚未证明的恢复边界。
- 区分 `AgentTraceMiddleware`、`JsonlTraceExporter` 与 `SessionTree`，去掉现成 traceId 查询和完整 trace 的过度承诺。
- 去掉框架已内置 Redis / MySQL / PostgreSQL state store 的过度承诺。

## 5. 仍需用户决定或运行 Spike 的事项

以下事项没有在本次文档修订中替用户选择：

1. workspace 与 state store 的实际落盘位置及是否同盘管理。
2. 任务 0.2 / 0.3 / 0.4 是合并还是重排；生产 `HarnessAgent` Bean 同时依赖 workspace 与 Model。
3. 主模型、DeepSeek model ID。
4. 是否启用 fallback、fallback 指向及 `maxRetries`。
5. Spring AI 保留还是移除，以及若保留时的职责边界。
6. trace 重跑默认复用工具结果还是重新执行。
7. checkpoint fork/replay、跨进程 workspace 并发、subagent trace lineage 与 compaction 质量的运行验收契约。

## 6. 验收状态

本文仅记录 API Spike 实施者的证据与文档修订，尚未构成最终验收。需要由未参与本次修订的 subagent 独立核对 JAR 证据、文档一致性和 Issue #67 的范围。
