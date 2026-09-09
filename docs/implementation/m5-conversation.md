# M5 自然对话、持久化回合与上下文

真实编译原理五知识点功能链路已通过：30个自然语言回合、25道测验题、15张有来源卡片，包含实际SSE文本断线后的持久化和同requestId去重。正式18会话压缩对照仍待完成。用户已明确授权课程资料外传至DeepSeek用于测试，当前无此前授权阻塞。

## 入口与业务提交

`POST /api/learning/sessions/{id}/messages` 接受 `message` 和 `requestId`，返回会话及持久化 `turn`。同一会话的 requestId 唯一；相同内容重放返回原回合，不重复调用模型；不同内容使用同一 ID 返回冲突。同步旧客户端未提供 ID 时服务端生成一次性 ID，因此旧客户端不能据此保证网络重试去重。SSE 入口 `POST .../messages/stream` 强制提供 requestId。

每轮新建 AgentScope 2.0.1 ReActAgent，并从 MySQL 加载该会话的已压缩 AgentState。只暴露 `knowledge_search`、`learning_explanation_done`、`learning_quiz_publish`、`learning_quiz_submit`、`learning_cards_publish`。模型自主选择工具；旧 `expectedTarget` 和强制 tool choice 已从学习生产链路删除。发布工具成功后通过 SDK PostActing Hook 结束该回合，避免额外模型请求才提交已经完整的产物。

工具只暂存本轮意图，服务端校验后在短事务中提交。普通答疑没有意图，也可以成功保存，不强制推进。讲解要求本轮有真实检索，测验必须恰好五道四选一题，卡片必须恰好三张；检索来源、选项唯一、问题/卡面唯一以及相邻状态转换均由服务端检查。每轮最多一次状态转换。评分读取原始用户消息的五个编号选项，按服务端标准答案每题 20 分；模型不能代填或改分。

`learning_turns` 保存用户消息、最终助手回复、可见产物、原始上下文增量、待压缩上下文、phase、status、traceId 和执行尝试次数。公开 JSON 隐藏执行令牌及原始模型上下文；公开测验产物不含标准答案。`GET .../messages` 恢复完整的新回合列表，`GET .../turns/{turnId}` 查询失败或处理中状态。前端聊天历史与模型上下文各自持久化，不能用聊天全量回填替代压缩恢复。

## 事务与恢复

同一会话只允许一个活跃回合。取得执行权时锁会话行，分配令牌和 600 秒租约；模型调用在事务外，提交、摘要缓存写入和完成均校验当前令牌与有效租约。旧执行者失去执行权后不能覆盖恢复者的状态。

阶段为 `MODEL → ARTIFACTS_COMMITTED → COMPLETED`，状态为 RUNNING、FAILED 或 SUCCEEDED。模型或验证失败时保留失败回合，不提交本轮业务产物。产物和未压缩的完整本轮上下文一起提交后才做摘要；摘要失败时保留该阶段，阻止其他新请求越过它。恢复同一 requestId 会跳过模型与产物写入，只继续摘要与最终提交。三卡和摘要均保存后，知识点才进入 COMPLETED。

完成最后一个知识点时显式将 `active_knowledge_point_id` 写为 NULL；不能依赖 MyBatis 的非空字段更新。旧实现的这处遗漏已补上。

升级时首次使用旧会话，会显式读取原 `state/ReActAgent/{user}/{session}/agent_state.json`，检查身份、解析与工具配对后存入 MySQL，原文件保留。旧历史没有知识点标签，不能声称追溯拆分了其中每个知识点；之后新增消息有独立标签。已开始的旧会话缺少原状态文件时明确报错，不恢复为空白上下文。旧版未持久化的完整聊天不被虚构为新回合记录。

## 压缩对照契约

统一配置在 `LearningConversationProperties`：阈值 8000 本地估算 token、摘要输出最多 700 token、学习回复最多 3000 token、最多 5 次 Agent 迭代、单个模型请求一次尝试。SDK 的 `maxRetries=1` 在当前版本表示一次尝试，同时显式设置 ExecutionConfig.maxAttempts=1。

| 策略 | 知识点边界 | 阈值触发 |
|---|---|---|
| THRESHOLD | 不额外摘要 | 压缩历史，尽量保留最新完整回合 |
| WHOLE_HISTORY | 摘要全部已保存历史 | 同上 |
| LOCAL | 只摘要当前知识点标签下的消息 | 同上 |

THRESHOLD 阶段将旧知识点历史与当前知识点前缀分别摘要，避免把当前点混入无标签的全局摘要，导致 LOCAL 之后无法隔离。最新回合超过半个阈值时也可纳入摘要，不能无限保留一个巨大工具回合。所有选择按完整消息和完整回合进行，压缩前后检查工具调用/结果成对。旧摘要在 LOCAL 边界不重复摘要，只有全局预算需要时进入阈值压缩。

成功摘要立即按 `(turnId, kind, inputHash)` 缓存，输入指纹含 prompt 版本、模型、预算及实际内容，排除随机消息 ID。若第二组摘要失败，重试会复用第一组成功结果。摘要调用使用相同 ObservedModel 账本，输入、输出、缓存输入及失败尝试计数都进入正式实验，缓存输入不重复相加。这里的 8000 是固定本地 tokenizer 对状态 JSON 的估算触发值，不等于 provider 的实际 usage 或硬上下文上限。

`eval` profile 提供 `POST /api/eval/learning/sessions/{id}/compression`，接受 `strategy`，仅允许在第一条消息前设置并冻结。正式测试应固定计划、用户输入、RAG 与模型参数，对两门课程各五知识点、三策略各三次取样。现在没有压缩节省比例，也没有把单元测试中的摘要长度当作模型性能。

改动前 JAR 保存在 Git 忽略的 `.eval/baselines/m5-before-conversation/study-agent.jar`，SHA-256 为 `76abf81a5c3dad25cab27b296d8309e6f088453a3719f9131de84c042b71b17a`，对应规划 v3 打包及旧学习实现（源码基准 c42630b）。这是可恢复的实现基线，尚未补齐固定多点任务的性能基线。新的 WHOLE_HISTORY 对照用于在相同自然对话路径上隔离压缩策略，并非声称逐调用重现旧强制工具链。

## 流式输出与 trace

SSE 事件为 accepted、text、progress、result、failure。accepted 只代表收到请求，result.turn.status 才代表业务结果。工具参数增量不直接发给 UI；测验题与卡片通过已提交产物返回。当前 NEW 讲解阶段推送文本增量，其余状态为避免测验答案泄露先缓冲并返回最终结果，不声称每种回复均逐 token 推送。

连接关闭只停止传输，后台回合继续持久化。客户端使用 requestId 查询/重试同一回合，不能因断线创建新请求。当前不提供 token 事件重放；租约过期后的恢复仍是显式重试。

模型输入/完整输出、模型调用开始/结束、工具输入/输出与耗时、产物提交和压缩均关联 traceId。原始 payload 保留在数据库中，不由普通 trace 接口公开；usage 账本记录真实 provider 尝试。外部调用失败不能记为零 token，缺少 usage 仍为未知。

## 验证记录

- 第一批 16 项本地测试通过，包含原始答案解析、产物校验、消息配对及回合恢复编排；日志 `.eval/m5-conversation-local-tests.log`。
- 第二批 28 项定向测试通过，包含实际 AgentScope SDK 执行/停止/恢复、两次模型调用的检索与讲解、摘要部分失败缓存复用、丢失租约拒绝提交；日志 `.eval/m5-conversation-sdk-tests.log`。模型和检索响应来自测试内存桩，没有真实 provider 调用。
- 包含 SSE、旧上下文导入、模型输入/输出 trace 的完整测试共 199 项，0 failures、0 errors、3 项既有跳过；初次 clean test 暴露三处旧 schema 断言，更新后全量重跑通过。日志 `.eval/m5-conversation-full-tests.log`、`.eval/m5-conversation-full-tests-r2.log`。V11 已在本地 eval MySQL 应用，后端打包与部署通过。
- 2026-09-09 模型账本核对：394 次尝试，392 SUCCEEDED、2 FAILED，终态均存在；两次早期失败 usage 未知。本轮本地测试未增加 provider 计数。


真实数据库/API 阶段测试已通过，证据见 `docs/evidence/m5/local-conversation-recovery.json`，脚本 `scripts/verify-learning-local-recovery.py`。预置的是人工编写的 ARTIFACTS_COMMITTED 合成夹具，不含下载课件；阈值策略下无需额外摘要模型调用。两个并发恢复分别返回 SUCCEEDED/RUNNING，最终三卡数量为 3、attemptCount 为 2、会话和知识点 COMPLETED、两个活跃指针均 NULL。重复相同请求没有新增回合，不同内容同 ID 的业务码为 409、跨用户查询为 404（项目现有异常处理器的 HTTP 状态均为 400）。SSE 对已完成请求返回 accepted/result，未测生成中断线。模型账本 394→394。

首次夹具使用 MySQL 的本地 NOW，而应用 JVM 为 UTC，导致设置的“过期”租约仍在应用时间之后；这是夹具时间错误，已改用 UTC_TIMESTAMP 在隔离 v2 数据上通过。重复知识库名称也触发了现有唯一性校验，后改为运行目录命名。失败日志保留，不将这次测试写成进程崩溃或真实模型恢复验收。


用量统计新增`scripts/report-learning-usage.py`：从真实账本按`LEARNING/sessionId/turnId`和关联`COMPACTION/turnId/kind`汇总学习、摘要及其失败/重试，排除其它会话和规划调用。可用`--turn-id`补充仅恢复摘要的回合。缺失usage保留未知，缓存输入不重复相加；三项自编事件回归覆盖会话隔离、失败重试、未终结调用和异常账本。该工具尚未产出正式18会话实验报告，不能据记账测试宣称压缩收益。

正式实验准备脚本为`scripts/prepare-learning-replicas.py`：要求一份真实成功的五知识点规划，冻结其目标、顺序、主题、来源、时间与重点；每门课程创建三策略×三次的九个初始会话，轮换策略执行顺序。默认只生成本地初始SQL和清单，`--apply`只在`study_agent_eval`中插入NEW状态计划，再通过实际API核对计划、设置压缩策略。副本使用独立业务ID，不复制回合、答案、卡片或上下文；这些是实验初始条件，不代表九次规划成功。后续学习仍全部通过生产API和真实provider执行。

`run-learning-conversation-smoke.py --script`支持固定每个知识点的六条用户消息，按主题匹配计划，保存文件SHA-256；已开始的实验拒绝更换或遗漏原脚本。由此可以在相同输入中加入错误理解与跨知识点回忆检查。新增脚本通过语法/CLI检查，初始副本SQL在隔离MySQL事务内插入人工五点夹具：检查5知识点/1计划后回滚，会话残留为0，证据`.eval/m5-replica-sql-validation.json`。API配置与正式18会话尚未执行，不能计入完成数量。

## 2026-09-09 真实首轮与重启检查点

真实v4规划创建会话2097529021508513794。脚本收到实际text事件后关闭SSE，后台仍完成原回合2097529179709272065，持久化讲解文本并完成阈值摘要；但模型漏调learning_explanation_done，产物为QUESTION，未推进到EXPLAINING，因此完整学习流程失败。已强化NEW状态下的明确提交要求，保留模型自主选择与服务端校验，没有手动补写业务状态。证据`docs/evidence/m5/conversation-pilot-v1-review.json`；实际2次学习调用+1次摘要，总输入39355、总token41148，usage完整，见`conversation-pilot-v1-usage.json`。这是单轮功能证据，不能计算压缩节省比例。

副本工具增加`--pilot`，可对编译原理创建一个LOCAL功能会话，不计入两主课程正式实验。会话6200002417100118000已完成五点实测：89次provider尝试全部成功，包含32次摘要；累计输入904141、总token954821，usage完整。566条trace、32份摘要和最终持久上下文通过只读导出保存，脚本`collect-learning-evidence.py`；结果见`docs/evidence/m5/conversation-pilot-v2-review.json`及同目录usage文件。该数字仅是单策略功能试跑用量，不能计算压缩收益。

Windows全量测试曾遇到本机JDK UnixDomainSockets.connect错误；用户重启后WSL的8GiB配置生效，Linux clean package通过225项、0失败、0错误、3跳过，证据`docs/evidence/m5/linux-8gb-build.json`。后续规划改动另做定向回归，不混记为同一版全量测试。

正式对照脚本`run-compaction-experiment.py`按轮换策略、交错课程执行18个已准备副本，默认先跑两个会话供检查。冻结JAR、RAG配置、用户脚本、初始计划及执行/导出脚本哈希；任何更改都拒绝混入同一实验。每点必须包含讲解、五题、评分、三卡，追问及不完整提交可以在冻结脚本中选择出现。正式设计使用指定点位的错误纠正和跨点回忆，不重复全功能试跑的每点不完整答案测试。失败保留同requestId与全部usage，明确恢复前不启动后续会话。

formal-v1先行会话在第四点连续三次发布测验时，将位置字母填入要求完整选项文本的correctAnswer，达到步骤上限后保留EXPLAINING。工具说明与字段schema已明确该契约，服务端仍严格校验，不自动猜测答案。第三点回忆丢失待解决问题，因此`learning-compaction-v2`明确先保留用户误区/标记和未解决问题，再概括课程知识与进度；700上限保持不变。13项相关回归通过，正式v2须用新初始会话运行，不能混入v1数据。完整失败、实际进程恢复和观察性导出修订见`docs/evidence/m5/formal-v1-gate-review.json`。
