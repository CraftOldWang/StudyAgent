# AGENTS.md

本文件约束所有在本仓库工作的 agent，包括主 agent 与 subagent。

## 0. 本文件的地位

- 本文件是 agent 执行纪律与实现约束的唯一来源。`docs/design/001-全局设计与范围.md` 定产品范围，`docs/design/StudyAgent-技术设计方案.md` 定当前架构，`PROGRESS.md` 是唯一实时任务看板，根 `CONTEXT.md` 只维护领域术语。
- `.archive/` 与根目录的历史建议稿。
- 当前权威文档之间若存在冲突或多个可辩护答案，触及高影响边界或没有明确推荐时必须停下来交给用户决定；否则可以按明确推荐执行并留痕。
- 本文件的决策条款归用户所有。agent 可以指出条款过时或自相矛盾，但不得自行改写决策内容。


## 3. 包结构（已定，方案三：按子域分包，子域内按职责分包）

```text
src/main/java/com/studyagent/
  config/            所有 @ConfigurationProperties 与 @Configuration
  identity/          初始用户与服务端身份、权限 scope 解析
  common/
    exception/  response/
    json/            唯一的 JSON 工具
  model/             所有表实体
  mapper/            所有 MyBatis-Plus Mapper
  algo/              纯算法，不依赖 Spring：fsrs/ rrf/ metric/ chunk/
  agent/
    integration/     AgentScope 与 Spring Boot 胶水层
    governance/      工具治理补强（配额、写入条数上限、重试）
    skill/           学习垂类 skill 定义
    web/             Agent HTTP 入口（或顶层 web/agent/）
  rag/
    retrieval/  index/  embedding/  eval/
    web/
  ingest/
    upload/  storage/  parse/  chunk/  pipeline/  sync/
    web/
  learning/            学习垂类流程：计划、知识点生命周期、委派编排
    web/
  review/
    web/
  profile/             用户画像、长期记忆、轻量知识图谱
    web/
  eval/                RAG 评测、LLM as judge、黄金集、回归门槛
    web/
```


## 4. 代码风格

- 注释解释"为什么"，不解释"是什么"。不写显然的注释，也不写"这段代码做了什么"的复述。
- 配置项进 `config/` 下的 `@ConfigurationProperties`，不允许把配置字符串散落在业务类里。
- 主键统一 `Long`，时间统一 `LocalDateTime`。

## 5. 失败处理

- 不静默降级，不吞异常，不引入降级/mock provider 掩盖真实错误。
- 不用大范围 try-catch 包裹业务流程。只在需要转换业务状态、补充上下文、释放资源时捕获。（避免过度防御性编程）
- 外部依赖失败必须留下明确的状态和错误信息，不允许让调用方无法区分"成功"和"没做"。
- 长流程失败后必须能看出停在哪一步。

## 6 允许

- 用户已持续授权本私有仓库开发过程中的 `git commit` 与普通 `git push`。
- 允许按 §2 的约束使用 worktree，并使用 subagent 进行不相关模块的并行开发。
- 允许使用 subagent 来开发和验收。
- 允许使用 `gh` 在模块边界同步少量模块级 Issue；不再维护 GitHub Project。
- 允许使用 `some_apiKey` 中的阿里云百炼的 qwen3.7-text-embedding(与flash版本) 模型与deepseek的 flash/pro 的 apiKey

## 7. 防止过度工程

- **做最小可行改动。** 只实现明确要求的功能，不添加"可能用得上"的扩展点。
- **不超出 scope。** 任务边界外的问题停下上报，不要顺手改掉。
- **不做过度防御性编程。** 不为几乎不可能出现、当前没遇到的问题编写解决方案和大量无用代码。
- **不虚构可能的问题。** 只解决实际遇到的问题，不预设未来可能的边界情况。
- **任务必须明确边界与目标。** 布置任务时应说清要达到什么状态、哪些不在范围内。


