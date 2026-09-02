# D-Q16：决策与进度记录载体

> 状态：READY_FOR_DECISION

## 需要分别保存的事实

- 决策正文、契约、设计 diff；
- 讨论、质疑和审批意见；
- 当前状态、负责人、优先级与 blocked-by；
- 实现与验收证据；
- 离线 Agent 可读取的约束快照。

一个载体不一定适合拥有所有事实。

## 方案

### A. Markdown 权威，Project 单向投影

```text
Markdown/ADR/设计 PR
-> 合并后创建/更新 Issue
-> Project 保存 Status/Priority/Owner/Blocked
```

优点：设计有 Git diff、行级 review、离线可读、随代码版本化。

缺点：Project 状态不自动回写 Markdown；需要稳定 ID 映射和同步脚本。

### B. Issues/Project 权威，Markdown 是生成快照

```text
Issue/Project
-> CLI/API 定期导出
-> 生成只读 Markdown snapshot
```

优点：多人协作、看板、状态、依赖、负责人体验最好。

缺点：依赖认证和网络；快照可能滞后；Issue 正文缺少与代码一起的 diff/行级审查。

### C. 按事实分权，但禁止双写同一字段

```text
Markdown/PR = 决策正文与契约
Issue = 讨论入口、摘要、验收链接
Project = 状态、负责人、优先级、依赖
```

通过 `decision_id` 和 URL 关联；Issue 不复制 200 行设计正文，Markdown 不手工复制 Project 状态。

## 推荐

推荐 C。它实际上是用户原提议的严格化版本：

- 设计文档留仓库并走 PR review；
- Issue 承载讨论和工作项；
- Project 是执行控制面；
- 离线 Agent 使用自动生成的只读 snapshot；
- 每类事实只有一个 owner，避免双写。

建议字段：

```text
DecisionState
Priority
Area
Owner
Blocked
DesignUrl
ReviewUrl
```

标签：`kind:decision/task` 与 `area:agent/rag/ingest/review/profile/eval`。

同步必须单向，使用稳定 `decision_id`、issue node id 与 project item id；不能用标题或显示字段名作为主键。

## GitHub 能力边界

- 私有仓库 item 仍受调用者仓库权限约束；Project 可见不等于其所有 item 可见。
- Project 支持自定义字段、board/table/roadmap、JSON/TSV 导出。
- Issue dependency 支持 blocked-by/blocking；API/CLI 能读取，复杂 Project 字段自动化主要依赖 GraphQL/`gh project`。
- 远端不可达或 token 失效时，Agent 只能读取最近快照，不能把缓存冒充当前状态。

## 需要用户拍板

- A/B/C；
- 哪一处拥有 `DecisionState`；
- 是否现在就恢复 `gh auth` 并迁移，还是等 002 Runtime 设计完成后；
- snapshot 的生成触发、存放路径和是否提交 Git；
- 是否由自动化拒绝 Markdown/Project 状态双写。

## 一手来源

- [GitHub Repository Visibility](https://docs.github.com/en/repositories/creating-and-managing-repositories/about-repositories)
- [GitHub Project Visibility](https://docs.github.com/en/issues/planning-and-tracking-with-projects/managing-your-project/managing-visibility-of-your-projects)
- [GitHub Projects GraphQL API](https://docs.github.com/en/issues/planning-and-tracking-with-projects/automating-your-project/using-the-api-to-manage-projects)
- [GitHub Issue Dependencies](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-issue-dependencies)
- [GitHub Pull Request Reviews](https://docs.github.com/en/pull-requests/how-tos/review-pull-requests/reviewing-proposed-changes-in-a-pull-request)
- [gh project](https://cli.github.com/manual/gh_project)
