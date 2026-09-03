---
status: accepted
---

# Phase 2 单知识库工具 scope

Phase 2 MVP 先把一次学习会话收窄为一个知识库，权限范围由服务端验证并贯穿 AgentScope 调用；多知识库编排和独立知识库管理留待新的决策。

## Decision

- `learning_sessions.knowledge_base_id` 是单值必填字段。会话创建/恢复只接受一个知识库 ID；当前有效 scope 以 `documents(user_id, knowledge_base_id)` 存在性为准，不新增 `knowledge_bases` 表。
- 服务端先验证当前用户与该单一知识库的 `documents` 记录，再构建 `RuntimeContext(userId, sessionId)`，并把已验证的 `knowledgeBaseId` 作为服务端 extra 写入。该 extra 只是 scope 载体，不替代服务端权限校验。
- `knowledge_search` 的模型可见参数只有 `{query}`；user、knowledge base 和检索策略由服务端从会话 scope 注入。
- `review_card_write` 的模型可见参数只有 `{drafts: [{front, back, sourceChunkId}]}`；`userId`、当前 `knowledgePointId` 和 `knowledgeBaseId` 由服务端从会话/运行上下文绑定，并校验来源 chunk 属于同一 scope。
- 2.7 只验收单用户、单知识库 fixture：必须有匹配的 V2 `documents` 行、学习会话、知识点、来源 `document_chunks` 和对应 ES chunk；不创建 `knowledge_bases` 行，也不覆盖多库或空库管理。

## Considered Options

- 继续让模型提交 `knowledgeBaseIds` 或卡片中的 user/knowledge-point/KB 字段：表面灵活，但把权限与归属决策暴露给模型，且扩大 Phase 2 的契约，因此不采用。
- 先新增 `knowledge_bases` 表和成员关系：能支持独立 KB 生命周期，但超出单知识库学习 MVP，增加迁移和管理面，因此不采用。
- 会话持有一个经验证的 KB，工具只接收业务内容参数：服务端绑定清晰、fixture 小、后续可在新决策下扩展，因此采用。

## Consequences

- Phase 2 的依赖顺序固定为 `2.6 → 2.6a → scope/RuntimeContext 接线 → 2.1/2.2 工具 → 2.3/2.4/2.5 治理 → 2.7 端到端 fixture`；schema/model 未就绪时不做最终工具验收。
- 工具 schema 不再携带权限字段，跨用户、跨 KB 和跨知识点写入必须由服务端拒绝；未来多 KB 或独立 KB 管理必须另开决策，不在本 ADR 中隐式扩展。

跟踪任务：Phase 2.1–2.7。
