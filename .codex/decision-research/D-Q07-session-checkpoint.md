# D-Q07：Session 与 Checkpoint

> 状态：READY_FOR_DECISION  
> 与 D-Q05、D-Q11、D-Q17、D-Q18 联合设计

## 概念边界

- Session：一次可跨多个用户 turn 的学习交互容器。
- Run：一次具有目标、预算和终止状态的执行。
- Turn：从一个外部输入开始，运行到等待输入、进入 review、完成或失败。
- Step：一次模型调用，或一个明确的 Runtime 推进单元。
- Checkpoint：已经持久化、允许安全恢复的边界与恢复材料。

Checkpoint 不是简单的 summary，也不等于数据库事务提交。

## 持久化方案

### A. Snapshot 为主

每个稳定点保存完整运行状态 JSON。

优点：恢复快。缺点：难以解释状态如何形成，snapshot 覆盖与并发更新容易冲突，轨迹评测能力弱。

### B. Event 为主

只保存 append-only event，恢复时完整重建。

优点：审计与 replay 最强。缺点：恢复成本、schema 演进和 projection 复杂。

### C. Event + Snapshot

事件是事实源；在稳定 event seq 创建 snapshot，恢复时加载 snapshot + 后续增量。

优点：兼顾可解释性和恢复速度。缺点：必须验证 snapshot 与 event seq 一致。

推荐 C。

## 崩溃后接管方案

### 1. 继续半截 Step

尝试从模型流或工具执行中点继续。

风险最高：模型请求一般无法从中间 token 精确续跑；外部工具是否已生效可能未知。

### 2. 关闭中断 Step，再从稳定点重新规划

加载最后 checkpoint，把 open step/turn 记为 `INTERRUPTED`；已完成工具结果按 D-Q18 选择复用或重新执行，再发起新 step。

优点：状态清晰、容易验证。缺点：可能多一次模型调用，写工具必须有幂等键。

### 3. 按操作类型恢复

模型调用重新执行；只读工具可重试；写工具先查幂等记录；异步任务重新 attach。

最精细，但首版复杂。

推荐以 2 为核心，并为工具按 3 处理副作用。

## 稳定 Checkpoint 候选

- 用户消息和 `turn.started` 已持久化后；
- 模型完整 response/tool calls 已持久化后；
- 一批工具结果按顺序 commit 后；
- Context compaction 完成后；
- 进入 `wait_user` 或 `review` 前；
- verifier 输出和业务状态事务完成后。

不建议在 token delta 或正在执行的外部副作用中间声明 checkpoint。

## 推荐恢复状态

```text
RUNNING
  -> WAITING_USER | REVIEW | COMPLETED
  -> FAILED | CANCELLED | INTERRUPTED

恢复：
INTERRUPTED
  -> RECOVERING
  -> RUNNING | REVIEW | FAILED
```

租约应独立于业务状态：`lease_owner/lease_until` 只决定谁能推进，不代表 Run 成功。

## 需要用户拍板

- A/B/C；
- 崩溃后选择 1/2/3；
- Run 表示整个学习计划，还是一次可执行任务；
- Turn 是否允许跨 HTTP 请求；
- 是否首版引入 lease；
- snapshot 保存哪些派生状态，哪些必须从 event 重建。

## 实现参照

- DeepSeek Harness session/flush：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/index.ts:1009`
- DeepSeek Harness crash close：`D:/1Learningoutput/deepseek-harness/packages/core/session/src/index.ts:1121`
- Pi session tree：`D:/1Learningoutput/javabackend/pi/packages/coding-agent/src/core/session-manager.ts:845`
