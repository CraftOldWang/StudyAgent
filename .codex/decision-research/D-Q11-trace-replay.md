# D-Q11：Trace、事件模型与回放

> 状态：READY_FOR_DECISION  
> 与 D-Q07、D-Q17、D-Q18 联合设计

## 两层模型

### Observability Trace

使用 W3C Trace Context / OpenTelemetry：

- `trace_id` 关联一次跨组件调用；
- span 表示 `run/turn/model/tool/retrieval/verifier` 等有持续时间的操作；
- attributes 保存耗时、模型、token、错误码等观测指标。

它适合性能、错误、调用拓扑，不适合做业务恢复事实源，因为 exporter 可能采样、丢失或改变保留策略。

### Durable Agent Event

追加式业务事实，例如：

```text
run.started
turn.started
context.compiled
model.requested / model.completed
tool.requested / admitted / completed / failed
checkpoint.created
review.started
verification.completed
turn.completed / interrupted
run.completed / failed
```

它服务于恢复、轨迹评测、审计和确定性投影。

## TraceId 传播方案

### A. 每 HTTP 请求一个 traceId

简单，但一个长期 Run 会被拆成多个互不相干的 trace。

### B. Session/Run ID 直接当 traceId

违反标准 trace 生命周期；超长 trace 可能不适合后端观测系统。

### C. 每次外部请求/Turn 新建 traceId，事件中同时保存 sessionId/runId/turnId

最适合长期 Agent：业务 ID 负责持久关联，traceId 负责一次执行链路。跨异步任务使用 span link 或持久 causation id。

推荐 C。

## Durable Event 最小字段

```text
event_id
session_id / run_id / turn_id / step_id
seq
event_type / schema_version
occurred_at
causation_event_id / correlation_id
trace_id / span_id
payload or payload_ref / payload_hash
producer / model_version / tool_version / prompt_version
ignorable
```

`seq` 应定义在明确的聚合范围内；首版可按 run 单调递增，避免全局序列瓶颈。

## 回放层级

1. UI playback：按原事件重新展示轨迹。
2. State rebuild：从事件重建 Run/Turn/Context 投影。
3. Recorded replay：复用录制的模型/工具结果测试新 projection/evaluator。
4. Model replay：重新调用模型，但复用工具记录。
5. Live rerun：模型与工具都重新执行。

这五种能力的数据要求和风险不同，不能都叫“回放”。

## Payload 存储选项

- 全量数据库 JSON：查询方便，表膨胀快；敏感数据治理压力大。
- 大 payload 放对象存储，事件表保存引用/hash/摘要：更适合长期 trace，但查询与保留复杂。
- 分级：状态事实和小 payload 在库；完整 prompt/tool result 按策略放对象存储。

推荐分级存储，并对 prompt/tool result 默认脱敏、加 retention policy。

## 方案代价与项目适配

- 只使用 OTel：接入快、性能观测成熟，但不能可靠恢复业务状态，也无法保证未采样轨迹可回放。
- 只使用业务 Event：恢复和审计清晰，但缺少标准跨服务传播、耗时拓扑和现成 exporter。
- OTel + Durable Event：职责最清楚，代价是两套 ID/生命周期需要建立稳定关联。

若目标同时包含“按 traceId 排障”和“崩溃恢复/轨迹评测”，双层方案最匹配；它仍需用户确认，不能直接视为已定架构。

## 需要用户拍板

- 是否采用 OTel + Durable Event 双层；
- traceId 传播 A/B/C；
- run 内 seq 还是 session 内 seq；
- 是否保存 token delta/chunk 事件；
- 大 payload 存数据库还是对象存储；
- 首版承诺哪些 replay 层级。

## 一手来源与实现参照

- [OpenTelemetry Trace API](https://opentelemetry.io/docs/specs/otel/trace/api/)
- [OpenTelemetry Events](https://opentelemetry.io/docs/specs/semconv/general/events/)
- DeepSeek Harness durable event types：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/types.ts:236`
- DeepSeek Harness surface projection：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/surface.ts:293`
