---
status: accepted
---

# AgentScope 承担通用 Agent Runtime

StudyAgent 从自建 Agent Runtime 换轴到 AgentScope Java 2.x。AgentScope 提供通用 Loop、Context、Memory、Session、Checkpoint、Trace 与 subagent 机制；StudyAgent 只拥有框架集成、项目级策略、工具治理、业务状态映射以及学习垂类能力，因为继续维护一套平行 Runtime 会重复框架职责并扩大不可验证的迁移范围。

因此，早期自建 Runtime 的表结构、模块职责和相关 D-Q 选择被本 ADR 取代，不作为兼容迁移目标。若 AgentScope 缺少项目必需能力，先用独立 Spike 形成运行证据，再由用户决定补强边界；不得因旧代码仍存在而默认沿用。跟踪任务：[Issue #66](https://github.com/CraftOldWang/StudyAgent/issues/66)。
