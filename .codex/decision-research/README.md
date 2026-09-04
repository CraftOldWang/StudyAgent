# StudyAgent 决策研究台

> 更新时间：2026-09-04
> 用途：保存历史技术研究、候选方案、机制证据与取舍背景。本目录和已归档的 `DECISIONS.md` 都不是当前设计权威。

## 使用规则

- 本目录只提供决策辅助，不替用户拍板。
- 当前权威依次为：根 `AGENTS.md` 的执行纪律、`docs/design/001-全局设计与范围.md` 的产品范围、`docs/design/StudyAgent-技术设计方案.md` 的当前架构，以及 `PROGRESS.md` 的实时状态。
- 本目录中的状态、依赖图和 `DECIDED` 标签只描述历史研究过程；与当前权威文档冲突时一律不采用。
- Agent Runtime 的代码参照只使用本地 `deepseek-harness` 与 `pi` 实现。
- 外部事实优先引用官方文档、官方规范、官方仓库和论文原文。
- 每个问题区分 `RESEARCHING`、`READY_FOR_DECISION`、`BLOCKED_WITH_RESEARCH`、`DECIDED`。
  - `BLOCKED_WITH_RESEARCH` 表示选项和约束已调研，但前置决策未完成，暂时不能拍最终结论。
  - `DECIDED` 是历史标签；结论只有进入上述当前权威文档或仍有效 ADR 后才可执行。

## 关键阻塞依赖

下图表达“最终设计被什么阻塞”，不是完整的研究先后顺序。各文档中的“前置/联合设计”章节拥有更细的依赖说明。

```text
D-Q02 Spring AI 边界
  -> D-Q03 Loop + D-Q04 Tool Governance
  -> D-Q05 Context + D-Q07 Checkpoint + D-Q10 Prompt
  -> D-Q11 Trace/Replay + D-Q18 Replay Mode
  -> D-Q17 新 V1 Schema

D-Q06 Memory ---------------------------> D-Q17
D-Q09 Multi-agent ----------------------> D-Q17
D-Q12 Evaluation -----------------------> D-Q17 的 eval 表族
D-Q13 ASR ------------------------------> D-Q17 的 transcript 表族

D-Q06 Memory + D-Q09 Multi-agent
  -> 学习流程、画像与子 Agent

D-Q01 ES Client + D-Q08 Chunk/Retrieval
  -> D-Q12 Evaluation -> D-Q17 的 rag/eval 表族

D-Q13 ASR 独立进入 ingest 设计
D-Q15 Exception、D-Q16 Tracking 可独立决定
```

## 当前状态

| 问题 | 状态 | 文档 |
| --- | --- | --- |
| D-Q01 ES 访问方式 | READY_FOR_DECISION | [D-Q01-elasticsearch-client.md](D-Q01-elasticsearch-client.md) |
| D-Q02 Spring AI 是否保留 | READY_FOR_DECISION | [D-Q02-spring-ai.md](D-Q02-spring-ai.md) |
| D-Q03 Agent Loop 归属 | READY_FOR_DECISION | [D-Q03-agent-loop.md](D-Q03-agent-loop.md) |
| D-Q04 工具治理 | READY_FOR_DECISION | [D-Q04-tool-governance.md](D-Q04-tool-governance.md) |
| D-Q05 Context | READY_FOR_DECISION | [D-Q05-context.md](D-Q05-context.md) |
| D-Q06 Memory | READY_FOR_DECISION | [D-Q06-memory.md](D-Q06-memory.md) |
| D-Q07 Session/checkpoint | READY_FOR_DECISION | [D-Q07-session-checkpoint.md](D-Q07-session-checkpoint.md) |
| D-Q08 Chunk/Retrieval | READY_FOR_DECISION | [D-Q08-chunk-retrieval.md](D-Q08-chunk-retrieval.md) |
| D-Q09 Multi-agent | READY_FOR_DECISION | [D-Q09-multi-agent.md](D-Q09-multi-agent.md) |
| D-Q10 Prompt | READY_FOR_DECISION | [D-Q10-prompt.md](D-Q10-prompt.md) |
| D-Q11 Trace/Replay | READY_FOR_DECISION | [D-Q11-trace-replay.md](D-Q11-trace-replay.md) |
| D-Q12 Evaluation | READY_FOR_DECISION | [D-Q12-evaluation.md](D-Q12-evaluation.md) |
| D-Q13 ASR | READY_FOR_DECISION | [D-Q13-asr.md](D-Q13-asr.md) |
| D-Q15 Exception | READY_FOR_DECISION | [D-Q15-exception.md](D-Q15-exception.md) |
| D-Q16 Issues/Project | READY_FOR_DECISION | [D-Q16-tracking.md](D-Q16-tracking.md) |
| D-Q17 新 V1 Schema | BLOCKED_WITH_RESEARCH | [D-Q17-v1-schema.md](D-Q17-v1-schema.md) |
| D-Q18 Tool Replay | READY_FOR_DECISION | [D-Q18-tool-replay.md](D-Q18-tool-replay.md) |

## 本地 Runtime 参照

参见 [runtime-reference-dsh-vs-pi.md](runtime-reference-dsh-vs-pi.md)。
