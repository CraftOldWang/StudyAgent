# IDEA 与单知识点对话修复 · 2026-09-09

当前对话行为以用户本轮确认的方案为准：

`开始聊天 → 讲解/自由追问 → 用户继续 → 选择题 → 交卷/错题解释 → 自由追问 → 用户继续 → 卡片草稿 → 编辑/重写 → 页面确认全部 → Anki → 下一知识点`

## 实现入口

| 入口 | 职责 |
|---|---|
| `StudyAgentApplication`、`application-local.yml` | IDEA 默认 local；Windows Java 运行后端，Docker 只运行中间件；使用当前 eval 数据，不改旧数据库迁移历史 |
| `LearningConversationGateway`、`LearningConversationConfiguration` | 恢复 Agent 上下文，带整体计划与当前状态，按阶段提供工具；普通问答不强制转换状态 |
| `LearningTurnIntent`、`KnowledgePointLifecycle` | 校验出题、交卷、进入写卡等业务动作；题目和卡片 1–10 个，选择题每题四选一；确定性评分 |
| `LearningContextMessages` | 复用当前知识点保留的真实资料工具结果；大纲里的来源 ID 和摘要中的文字引用不冒充原文证据 |
| `LearningCardStageService` | 进入写卡时保存此前上下文并异步摘要；保存草稿、等待确认、写入 Anki、完成知识点 |
| `LearningConversationCompactor` | 普通阶段保留阈值压缩；卡片待确认期间不替换原上下文；确认后才用预生成摘要替换整个知识点 |
| `LearningPanel`、`ToolCalls`、`CardDrafts` | SSE 对话、默认折叠工具参数/结果、选择题、草稿编辑与显式确认；旧草稿折叠且不可重复导出 |

状态：`NEW → EXPLAINING → QUIZZING → FEEDBACK → CARD_GENERATING → CARD_CONFIRMING → COMPLETED`。
`CARD_CONFIRMING` 用于写入 Anki 和等待摘要，失败显示错误，保留原上下文，可重试确认。草稿不是完成状态。

保存的最新卡片会在重写轮次末尾重新提供给模型，避免它照搬历史工具里的旧草稿。直接编辑后先点“保存修改”，再通过对话要求重写；模型仅写草稿，不能替用户确认。

## 本机启动

见根 README 的“IDEA / 本机开发启动”。JDK 21，项目根目录作为 working directory，运行 `StudyAgentApplication` 即可。Flyway 已通过并应用 V16，不执行 repair，不关闭校验。

两个网络问题分别处理：
- RocketMQ：`brokerIP1=rocketmq-broker`，本机 hosts 已有 `127.0.0.1 rocketmq-broker`；无需改变现有 broker 配置。
- Java Windows socket：不依赖 MQ 的 `Selector.open()` 最小程序也在系统 TEMP 失败；启动入口改用项目 `.eval/sockets` 后正常。

## 实际验收

会话 `2097690269060263938`，编译原理课件，第一知识点“编译器的分析-综合模型”。真实 DeepSeek，通过后端 API 和浏览器操作完成：

- 初始讲解、自由追问；3 道题通过页面作答，2 对 1 错，67 分。
- 交卷后保留 FEEDBACK，自由追问得到解释；用户继续后才生成 2 张卡。
- 写卡时摘要输入 16 条消息，不包含卡片工具调用。摘要完成后原上下文仍保留；编辑和两次重写期间摘要及其输入不变。
- 手动改卡已持久化；首次重写覆盖编辑被发现并修复，复测保留第一张修改、仅重写第二张。
- 确认前上下文 31 条，Anki 无对应笔记；页面确认后新增两条笔记 `1788965722207`、`1788965722425`，内容包含最终编辑。
- 确认后仅剩 1 条预先生成的摘要，卡片阶段消息和工具记录全部从模型上下文移除；数据库聊天历史保留。
- 下一轮的实际模型输入是“同一条摘要 + 新用户消息”，模型正确回顾语法树错题，再开始第二知识点讲解，状态 EXPLAINING。
- 写卡后经历本机后端重启，待用摘要从数据库恢复并完成确认。

原始会话、前后上下文及 Anki 结果位于 `.eval/dialogue-demo/`，汇总 `acceptance.json` 为 PASS。这是功能验收，不是 token 节省比例或 RAG 召回提升实验。

相关后端状态/工具/题数/摘要测试、前端组件测试及构建通过。来源复用补丁的 10 项相关测试通过；补充的实际 SDK 系统提示恢复检查通过。没有重复运行全量测试或旧 18 会话矩阵。

## 本次边界

尚未改造多层大纲规划；当前 Agent 带现有计划及章节信息。旧实验知识库散布在不同 ES 索引，本轮课件通过计划来源读取；不能据此声称所有历史实验库都能在当前索引检索。正式性能/压缩对照和资料库整理仍属于之后另行确定的任务。
