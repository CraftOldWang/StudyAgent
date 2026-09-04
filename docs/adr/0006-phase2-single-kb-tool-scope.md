---
status: accepted
---

# 单知识库会话与服务端工具 scope

首个里程碑把一次学习会话收窄为一个知识库，权限范围由服务端验证并贯穿 AgentScope 调用。知识库本身需要最小实体和管理入口，多知识库会话留待后续。

## Decision

- 新增最小 `knowledge_bases` 表，明确保存知识库归属与名称；提供创建、列表、重命名和文档列表，不做删除与级联清理。
- `learning_sessions.knowledge_base_id` 是单值必填字段。会话创建/恢复只接受一个属于当前用户的知识库 ID。
- 服务端先验证当前用户与该单一知识库的归属，再构建 `RuntimeContext(userId, sessionId)`，并把已验证的 `knowledgeBaseId` 作为服务端 scope 写入。运行上下文只是 scope 载体，不替代服务端权限校验。
- `knowledge_search` 的模型可见参数只有 `{query}`；user、knowledge base 和检索策略由服务端从会话 scope 注入。
- 卡片写入时，`userId`、当前 `knowledgePointId` 和 `knowledgeBaseId` 由服务端从会话/运行上下文绑定，并校验来源 chunk 属于同一 scope。

## Considered Options

- 继续让模型提交 knowledge-base 或 user/knowledge-point 字段：表面灵活，但把权限与归属决策暴露给模型，因此不采用。
- 继续用 `documents(user_id, knowledge_base_id)` 的存在性代替知识库实体：表少，但空知识库无法存在，创建和重命名也没有稳定聚合，因此不采用。
- 建立最小知识库实体，会话持有一个经验证的 KB，工具只接收业务内容参数：归属与权限边界清晰，同时没有成员、删除或级联清理的额外范围，因此采用。

## Consequences

- 工具 schema 不携带权限字段，跨用户、跨 KB 和跨知识点访问必须由服务端拒绝。
- 空知识库可先创建再上传文档；知识库删除、多用户成员关系和多 KB 会话不在首个里程碑内。
- M1 先完成知识库实体和真实 RAG 工具，M2 再把同一 scope 绑定到学习会话。
