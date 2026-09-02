# D-Q12：LLM-as-Judge 与轨迹评测

> 状态：READY_FOR_DECISION  
> 前置：D-Q08、D-Q11；学习效果还依赖 learning/profile 设计

## 五个评测对象

1. Retrieval：Recall@K、MRR、nDCG、证据覆盖和 parent-context recall。
2. Grounded Answer：claim 是否被证据支持，引用是否正确，无依据时是否拒答。
3. Tool/Trajectory：工具选择、参数、顺序/并发、错误恢复、预算、权限与重复调用。
4. Goal Success：最终业务状态是否满足验收条件；优先使用规则、测试和 verifier。
5. Learning Outcome：独立回忆、迁移题、延迟保持和用户校准；不能用“回答写得好”代替。

## Evaluator 选项

| 方式 | 优点 | 风险 |
| --- | --- | --- |
| 规则/程序 evaluator | 可重复、可解释、适合 schema/状态/工具/引用 | 不能覆盖开放式教学质量 |
| 单 Judge 绝对评分 | 接入快、成本低 | 位置、冗长、自偏好、prompt 和模型版本敏感 |
| Pairwise Judge | 更适合 A/B 比较 | 必须交换顺序；只给相对偏好 |
| Multi-Judge | 降低单次偶然性 | 相关偏差、聚合规则与成本更高 |
| Human Calibration | 能测量 Judge 是否可信 | 标注成本和一致性问题 |

## 推荐组合

- 规则 evaluator 是底座，决定权限、schema、状态、工具错误与引用存在性等硬条件；
- 单 Judge 只做 groundedness、解释质量、教学清晰度等软指标辅助；
- A/B prompt/model 对比使用 pairwise，并交换候选顺序；
- 建立小规模人工金标，测 Judge 与人工的一致性和错误类型；
- Judge 不直接修改业务状态，不单独决定画像或学习掌握度；
- 同一 case 允许重复运行，报告均值、方差/置信区间和失败率。

## 版本与数据要求

每个 evaluation run 至少记录：

```text
case/dataset/corpus version
prompt/model/provider/tool catalog/retriever version
run/trace id
retrieved ids/scores/citations
messages/tool calls/results/final state
rule verifier output
judge model/rubric/prompt/temperature
human label and adjudication
latency/tokens/cost
```

低分 Trace 只能成为“候选回归样本”；人工确认输入、预期与失败标签后才能进入 Golden Dataset。

## 需要用户拍板

- 首版五层中实现哪些；
- Judge 使用 absolute、pairwise 或两者；
- 是否允许 Judge 参与 verifier，还是严格只做离线评测；
- 人工金标数量、抽样和争议仲裁方式；
- learning outcome 首版是否只做即时测验，还是加入延迟复测。

## 一手来源

- [MT-Bench / Chatbot Arena Judge Study](https://arxiv.org/abs/2306.05685)
- [Position Bias Study](https://arxiv.org/abs/2406.07791)
