---
status: accepted
---

# AgentScope 承担通用 Agent Runtime

StudyAgent 从自建 Agent Runtime 换轴到 AgentScope Java 2.x。AgentScope 已证实提供通用 Loop、Context、Memory、`AgentState` / `AgentStateStore`、Trace 与 subagent 扩展点；checkpoint fork/replay 不进入首个里程碑。StudyAgent 只拥有框架集成、项目级策略、工具治理、业务状态映射以及学习垂类能力，因为继续维护一套平行 Runtime 会重复框架职责并扩大不可验证的迁移范围。

AgentScope 是唯一 Agent Runtime。首个里程碑删除全部 Spring AI 代码和依赖：Chat/Tool 运行链不保留兼容层，embedding 改由阿里云官方 `dashscope-sdk-java` 直接实现，并显式区分 DOCUMENT/QUERY。该说明明确既定换轴的清理终点，不是架构换向。

因此，早期自建 Runtime 的表结构、模块职责和相关 D-Q 选择被本 ADR 取代，不作为兼容迁移目标。首版只保存最新 AgentState；若后续需要 checkpoint/replay，再用运行证据确认 AgentScope 边界并由用户决定补强范围，不得因旧代码仍存在而默认沿用。
