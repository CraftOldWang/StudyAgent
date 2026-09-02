# D-Q18：重跑时复用工具结果还是重新执行

> 状态：READY_FOR_DECISION  
> 前置：D-Q04、D-Q07、D-Q11

## 三种模式

### RECORDED

复用已记录的工具结果，不执行真实工具。

适合：Prompt/model/Context/evaluator 的可比回归、轨迹展示、故障分析。

要求：保存 tool name/version、规范化参数、结果/错误、权限上下文、side-effect 级别、结果 hash 和 source event。

风险：不能证明当前索引、外部服务或数据仍然正确。

### LIVE

重新执行所有工具。

适合：验证当前系统真实状态与修复后的端到端行为。

风险：数据/索引漂移导致不可比；写工具可能重复副作用；必须重新授权、使用幂等键和当前 policy。

### MIXED

每个工具或步骤显式选择 RECORDED/LIVE：例如默认复用检索结果，明确批准后重跑只读检索；写工具永不自动 live replay。

优点：兼顾可比性和现实验证。缺点：报告必须标明每个结果来源，语义更复杂。

## 项目适配与推荐

推荐同时支持三种，但默认 RECORDED：

- 默认回归评测使用 RECORDED，证明 prompt/model/runtime 改动的影响；
- 端到端验证显式选择 LIVE；
- MIXED 用于诊断，不作为无标记的隐式行为；
- WRITE/EXTERNAL_SIDE_EFFECT 在 replay 中默认禁止 LIVE，除非单独授权且有幂等键。

每个 Run Contract 必须固定 replay mode，报告中显示 recorded/live 比例。

## 模型调用也需要记录

- provider/model/version；
- temperature/top_p/seed（支持时）；
- prompt/context/tool schema version；
- request/response hash；
- token usage、finish reason、错误分类。

否则无法区分代码改动与模型随机性。

## 需要用户拍板

- 默认 RECORDED、LIVE 或 MIXED；
- 是否首版只实现 RECORDED + 显式完整 LIVE；
- recorded payload 保存完整结果还是对象存储引用；
- 哪些 read tool 允许自动 live replay；
- 是否永远禁止写工具自动重跑。

## 实现参照

- DeepSeek Harness replay derives from canonical session log：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/index.ts:570`
- Pi 没有 deterministic replay 协议，但保存工具结果消息：`D:/1Learningoutput/javabackend/pi/packages/coding-agent/src/core/agent-session.ts:594`
