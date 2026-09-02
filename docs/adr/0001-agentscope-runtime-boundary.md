---
status: accepted
---

# AgentScope 承担通用 Agent Runtime

StudyAgent 从自建 Agent Runtime 换轴到 AgentScope Java 2.x。AgentScope 已证实提供通用 Loop、Context、Memory、`AgentState` / `AgentStateStore`、Trace 与 subagent 扩展点；checkpoint fork/replay 只是项目产品目标与待运行验证的恢复边界，不是已证实的公开 API。StudyAgent 只拥有框架集成、项目级策略、工具治理、业务状态映射以及学习垂类能力，因为继续维护一套平行 Runtime 会重复框架职责并扩大不可验证的迁移范围。

目标态中 AgentScope 是唯一 Agent Runtime。现有 Spring AI Chat/Tool 调用链只是在消费者逐步迁移期间保留的遗留兼容层，禁止新增消费者，迁移完成后删除；Spring AI Embedding 若暂留仅属于 RAG provider adapter，不构成第二套 Runtime。该说明只是明确既定换轴的迁移终点与清理边界，不是架构换向。

因此，早期自建 Runtime 的表结构、模块职责和相关 D-Q 选择被本 ADR 取代，不作为兼容迁移目标。若 AgentScope 缺少项目必需能力，先用独立 Spike 形成运行证据，再由用户决定补强边界；不得因旧代码仍存在而默认沿用。跟踪任务：[Issue #66](https://github.com/CraftOldWang/StudyAgent/issues/66)。
