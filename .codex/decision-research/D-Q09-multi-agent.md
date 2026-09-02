# D-Q09：多 Agent 协作模型

> 状态：READY_FOR_DECISION  
> 前置：D-Q03、D-Q07；与 D-Q04、D-Q06 联合设计

## 选项

### A. 同步进程内 Child Agent

父 Run 创建独立 child session，限制工具和预算，等待结构化结果。

优点：实现简单、低延迟、容易调试。缺点：父进程崩溃连带影响；长任务会占住请求。

### B. 异步进程内 Job

父 Run 提交本机 job，稍后收集结果。

优点：不阻塞当前请求。缺点：重启恢复和跨实例调度弱，容易形成“伪可靠队列”。

### C. 消息驱动 Worker

任务进入队列，由 worker 领取、续租、执行、回传。

优点：长任务、故障隔离、水平扩展和背压更好。缺点：必须设计 lease、幂等、取消、消息版本、重复投递与结果回注。

### D. 混合

短 fan-out 走 A，长任务/异步媒体处理等走 C。

## Child Agent 必须有的任务契约

- 独立 session/run id 和 parent lineage；
- 冻结的 user/KB permission snapshot；
- provider/model/persona/prompt/tool whitelist；
- token、模型调用、工具、时间、递归深度预算；
- 结构化 output contract；
- accepted/running/succeeded/failed/cancelled/timed_out 状态；
- 显式结果回注，不把完整内部 transcript 直接污染父 Context；
- 外部副作用幂等与审计。

## 子 Agent 是否直接写业务状态

三种方案：

1. 允许直接写：最快，但 ownership、部分失败和回放复杂。
2. 只能返回 proposal/artifact，由父 Runtime/业务服务验证后提交。
3. 只允许调用已有受治理写工具；工具本身负责权限、幂等和事务。

推荐 2 + 3：Quiz/Card child 生成候选结果；最终写入由 `learning/review` 业务能力验证，或通过受治理工具执行。`agent` 不应理解知识点/卡片业务语义。

## 推荐的演进路线

- 首版 A：同步、有界 fan-out、独立 session、结构化返回；
- 一开始就保留 task contract/lineage/budget 接口；
- 只有任务超过交互时延、需要重启恢复或跨实例扩展时，再升级 D；
- 不做固定多角色讨论/投票网络；D-009 当前需要的是有界委派。

## 需要用户拍板

- A/B/C/D；
- 哪些任务允许同步等待，超时后是取消还是转后台；
- 子 Agent 写业务状态选择 1/2/3；
- 首版最大 fan-out、递归深度和总 token budget；
- 父 Agent 失败时 child 继续、取消还是转孤儿任务；
- child 完成后父 Context 注入完整结果、摘要还是 artifact reference。

## 本地实现参照

- DeepSeek Harness child identity：`D:/1Learningoutput/deepseek-harness/packages/subagent/subagent/src/continuation.ts:394`
- DeepSeek Harness delegation snapshot：`D:/1Learningoutput/deepseek-harness/packages/subagent/subagent/src/continuation.ts:418`
- DeepSeek Harness report/cancel：`D:/1Learningoutput/deepseek-harness/packages/subagent/subagent/src/continuation.ts:534`
