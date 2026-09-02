# AGENTS.md

本文件约束所有在本仓库工作的 agent，包括主 agent 与 subagent。

## 0. 本文件的地位

- 本文件是 agent 执行纪律与实现约束的唯一来源；当前架构与任务基线以 `docs/design/001-全局设计与范围.md`、`docs/design/StudyAgent-技术设计方案.md`、`docs/design/StudyAgent-执行方案.md` 为准。
- `.archive/` 与根目录的历史建议稿（`fable5建议.md`、`opus5建议.md`、`AGENT_RUNTIME_EVOLUTION.md`、`CONTEXT.md` 等）**不作为设计依据**。`DECISIONS.md` 是历史快照，其中与当前 `docs/design/` 冲突的条目已过期。
- 三份当前设计文档之间若存在多个可辩护答案，必须停下来交给用户决定；不得用旧决策、文档日期或 agent 自己的偏好裁决。
- 本文件的决策条款归用户所有。agent 可以指出条款过时或自相矛盾，但不得自行改写决策内容。

## 1. 最高原则：技术决策权属于用户

**任何有多个可辩护答案的技术选择，必须停下来把选项和代价交给用户，不得自行决定后继续实现。**

理由：本项目用于面试。如果 agent 替用户做了技术决策，最终交付的是一个用户无法解释、无法辩护的系统，项目也就失去了意义。宁可停下来问，也不要"合理地"替用户选。

- 需要问的：架构、分层、存储选型、算法选择、库和框架引入、数据模型、协议与契约、任何会写进简历的实现方式。
- 不需要问的：纯机械跟进——重命名、套用已经确认过的模式、修编译错误、补充已确认设计下的样板代码。
- 明令禁止的借口："采用合理默认值""业界通用做法""先实现一版后面再调"。这些都不能替代询问。
- 待决策清单内的任何一项，以及当前设计文档之间尚未消解的冲突，一律不得自行决定。不得因为旧 `DECISIONS.md` 曾写“已清空”就视为当前冲突已经关闭。

## 2. 主 agent 与 subagent 的分工

主线程的上下文是稀缺资源，承载的是设计推理和用户的决策记录；文件内容、构建输出、测试日志都是可以随时重新生成的廉价信息，不应该占用主线程。

- 主线程只做：设计讨论、决策记录、任务分解、验收结论。
- subagent 做：仓库勘察、具体实现、编译与测试、独立验收。
- **实现与验收必须由不同的 subagent 完成。** 写代码的 agent 不验收自己的代码。
- subagent 返回结论与 `file:line` 证据，不返回文件内容。
- 重复性、机械性的工作使用较小的模型（sonnet），不浪费在大模型上。
- 相互独立的 subagent 在一条消息里一次性发起，让它们并行。
- **subagent 不得擅自扩大任务范围。** 遇到任务边界外的问题、或遇到需要决策的分歧，停下并上报，不要顺手改掉，也不要自行选择一种做法。

## 3. 包结构（已定，方案三：按子域分包，子域内按职责分包）

```text
src/main/java/com/studyagent/
  config/            所有 @ConfigurationProperties 与 @Configuration
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

约定：

- `model/` 只放表实体，不写行为。
- `algo/` 只放纯算法，不允许出现 Spring 注解或框架依赖，必须能纯单元测试。
- `mapper/` 与 `model/` 全局共享。这是刻意的：旧结构把实体和 Mapper 关在模块私有包里，反而逼出了六处"跨模块 import 对方 Mapper"和一组双向依赖。提到全局是为了溶解那个问题，不是偷懒。
- **Controller 及其 Request/Response 放在各子域的 `web/` 下**（或顶层 `web/<子域>/`，二者等价，D-020）。持久化全局、Web 局部，这个不对称是有意的：`mapper`/`model` 全局是为了溶解依赖问题，Controller 局部是因为 HTTP 入口天然属于一个业务域。
- **禁止再出现 `application/domain/infrastructure/interfaces` 四层结构。** 旧结构里 27 个 domain 文件只有 9 个有方法，四个模块的 domain 包是零方法的表实体集合；不要复制这种分层。
- 一个包对应一种职责。新增包之前先确认它不是已有包的同义词。

## 4. 代码风格

- 注释解释"为什么"，不解释"是什么"。不写显然的注释，也不写"这段代码做了什么"的复述。
- DTO、表实体、领域对象不混用。
- Controller 不写业务逻辑。Mapper 不写复杂业务判断。
- 事务边界必须明确，写在服务层，不要靠调用链隐式传播。
- 配置项进 `config/` 下的 `@ConfigurationProperties`，不允许把配置字符串散落在业务类里。
- 主键统一 `Long`，时间统一 `LocalDateTime`。
- **一个类只承担一类职责。** 超过 300 行必须在提交说明里给出理由。旧代码里有 1169 行和 872 行的类，各自同时承担了编排、业务规则、持久化、DTO 组装、prompt 拼装、JSON 解析——不要再产生这种类。
- 同一段逻辑不允许存在第二份拷贝。旧代码里 `extractJsonObject` 被逐字复制过两份，`toJson` 包装器被重复实现过多次；这类重复一旦发现就合并到 `common/json`。

## 5. 失败处理

- 不静默降级，不吞异常，不引入降级 provider 掩盖真实错误。
- 不用大范围 try-catch 包裹业务流程。只在需要转换业务状态、补充上下文、释放资源时捕获。
- 外部依赖失败必须留下明确的状态和错误信息，不允许让调用方无法区分"成功"和"没做"。
- 长流程失败后必须能看出停在哪一步。旧代码里 run 崩溃后永远停在 RUNNING、下次请求会被当成僵尸 run 继续追加，这是反面例子。

## 6. 硬禁止

- 禁止为了演示效果硬编码答案。
- 禁止在检索无结果时编造知识库来源。
- 禁止把 userId、知识库 ID 等权限条件交给模型决定。权限参数一律服务端注入。
- 禁止把密钥、token 写进任何文件、日志或提交。
- **禁止删除未被 git 跟踪的文件。** 本仓库有相当数量的未跟踪文件，删除即不可恢复。需要删除时先向用户确认。
- 禁止引用已作废的历史文档作为设计依据（见 §0）。

## 6.5 允许

- 用户已持续授权开发过程中的 `git commit`：单项任务通过独立验收后，使用精确 pathspec 提交，不再逐次确认。`git push` 不包含在这项持续授权内，只按用户当次明确的 push 范围执行；禁止 force push。
- 允许使用 worktree 并使用 subagent 进行不相关的模块的并行开发。
- 允许使用 subagent 来开发和验收。
- 允许使用 `gh` 来控制本项目关联的 github project。

## 7. Codex 执行约束

Codex (gpt-5.6-sol, xhigh thinking) 很容易过度工程。必须遵守：

- **做最小可行改动。** 只实现明确要求的功能，不添加"可能用得上"的扩展点。
- **不超出 scope。** 任务边界外的问题停下上报，不要顺手改掉。
- **不做过度防御性编程。** 不为几乎不可能出现、当前没遇到的问题编写解决方案和大量无用代码。
- **不虚构可能的问题。** 只解决实际遇到的问题，不预设未来可能的边界情况。
- **任务必须明确边界与目标。** 布置任务时应说清要达到什么状态、哪些不在范围内。

示例：
- ❌ "可能以后会有多种存储后端，先抽象一个 interface"（当前只用一种）
- ✅ "实现 MySQL 存储，后续需要切换时再抽象"
- ❌ "用户可能会输入超大文件，加个流式处理"（当前限制 10MB，没遇到问题）
- ✅ "按当前 10MB 限制实现，超限返回错误"

## 8. 任务验收标准

一个任务算完成，必须满足：

1. **功能完整**：明确要求的功能全部实现，没有"TODO" 或"暂时跳过"。
2. **编译通过**：`mvn compile` 零错误。
3. **测试通过**：相关测试执行且全部通过；新功能有测试覆盖。
4. **文档同步**：如果改动影响 API / 配置 / 部署，对应文档已更新。
5. **无明显缺陷**：独立验收 agent 审查后未发现逻辑错误、资源泄漏、安全问题。

## 9. 已知问题（是缺陷，不是可参照的设计）

改到相关位置时不要照抄，也不要假设它们是有意为之。

- `application.yml` 里三个 API key 以 `${ENV:默认值}` 形式提交了真实值，且该文件被 git 跟踪。视为已泄露。 （由于相关的deepseek API key 没有多少钱，目前使用这种简单API key存储方式。不要折腾不泄密的做法）
- `application.yml` 里 `configuration:` / `global-config:` 缩进在 `spring:` 下而非 `mybatis-plus:` 下，导致驼峰映射、`assign_id`、逻辑删除全部静默失效。
- `DEFAULT_USER_ID = 1L` 硬编码在七个服务里，全项目零鉴权，上传接口无鉴权且限额 1024MB。
- `HashEmbeddingService` 与 `SpringAiEmbeddingService` 都是无条件 `@Service`，仅靠后者的 `@Primary` 才不冲突；前者永远不会被注入。
- `TextChunker` 零测试，且边界吸附含硬编码魔数，而它直接决定检索质量。
- `QuizService` 已不再接入 agent，Planner 仍可输出 QUIZ 阶段但后端不生成测验。
- 上下文在单个 todo 内无长度上限，`token_count` 字段只写不读，撑爆 window 只会表现为 provider 报错。
