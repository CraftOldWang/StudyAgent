# D-Q10：Prompt 管理与结构化契约

> 状态：READY_FOR_DECISION  
> 与 D-Q02、D-Q05 联合设计

## Prompt 存放方案

### A. Java 字面量

优点：改动与代码绑定、容易跳转。缺点：长 prompt 难 review、难做内容 diff、容易复制 JSON schema。

### B. Git 管理的资源文件

使用 `resources/prompts/<name>/<version>.md`，frontmatter 保存 name、purpose、input variables、output contract id。

优点：可 diff、PR 审查、离线可读、随代码发布。缺点：修改需要发布。

### C. 数据库 Prompt Registry

优点：灰度、热更新、按模型/租户路由。缺点：需要审批、权限、缓存、回滚、审计和防止核心安全指令被任意覆盖。

### D. Git 定义内容，数据库只选择已批准版本

优点：既保留可审查内容，也支持运行时切换。缺点：仍需版本同步和启用映射。

## 结构化输出契约的唯一来源

可选：

1. Prompt 手写 JSON 示例；
2. 独立 JSON Schema 文件；
3. Java record/class 生成 JSON Schema；
4. Tool schema 作为结构化结果提交接口。

不建议 1；它正是当前三份 schema 人工同步问题的来源。

推荐首版：

- Java record 或独立 schema 文件是唯一契约；
- codec、validator、repair prompt 和 tool schema 都从同一契约生成；
- prompt 只引用 `contractId/version`，不复制 schema 文本；
- 每次模型调用记录 prompt name/version/content hash、contract version 和变量 hash。

如果 provider 的 native structured output 可用，仍要执行服务端 schema validation；官方说明 converter/模型结构化输出并不保证结果永远合法。

## 项目适配与推荐

首版选 B + Java/Schema 单一契约。只有出现真实的灰度/运行时切换需求，再升级为 D；不建议直接建设完整 DB prompt 平台。

## 需要用户拍板

- A/B/C/D；
- Java record 还是独立 JSON Schema 为 contract source；
- prompt version 使用人工 semver、内容 hash，还是两者并存；
- repair 是同一 prompt 的一次受限重试，还是独立版本化 prompt；
- system/policy prompt 是否禁止数据库覆盖。

## 一手来源与实现参照

- [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)
- [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses)
- Pi prompt loader：`D:/1Learningoutput/javabackend/pi/packages/agent/src/harness/prompt-templates.ts:24`
- DeepSeek Harness structured result tool：`D:/1Learningoutput/deepseek-harness/packages/subagent/subagent-in-process-driver/src/structured.ts:49`
