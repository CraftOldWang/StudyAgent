# M7 · Anki 单向导出

## 边界与机制

已持久化复习卡通过 `POST /api/review/cards/{cardId}/anki` 导出，`GET` 同路径查询状态。每张卡单独提交与反馈，旧会话卡片、聊天历史卡片复用同一按钮。只连接本机 AnkiConnect，不读取复习记录、不回写学习计划、不覆盖 Anki 中用户编辑的笔记正文。

`AnkiExportService` 先核验卡片用户归属；有来源时再次核验 chunk 对应文档的 user/KB。正反面按纯文本 HTML 转义，保留换行；来源包含知识库、卡片 ID、文档标题、chunk ID 及已有 source_location，缺少来源时明确显示未关联资料。

专用笔记类型 `StudyPilot v1` 的字段顺序为 `StudyPilotId / Front / Back / Source`。第一个字段为稳定的 `studypilot-u{userId}-c{cardId}`，卡片模板只显示正反面与来源。笔记按知识库进入 `StudyPilot::知识库 {id}` 牌组，不依赖本机语言对应的 Basic 模板名称。已有同名模板的字段或顺序不符时明确失败，不覆盖用户模板。

每次导出先查询稳定标识并核验笔记类型、精确字段值和生成的卡片，存在则复用 noteId。创建请求关闭重复笔记选项；Redisson 每卡锁串行同一卡片的并发请求，模型创建使用共享锁。网络请求不占用数据库长事务。Anki 已创建但响应丢失时记为失败，下一次先查询已有笔记，避免盲目重建。用户在 Anki 删除笔记后，再次导出可创建新笔记。

V14 在 review_cards 上增加 `anki_export_status/error/attempts/exported_at`，状态为 PENDING → EXPORTING → SUCCEEDED/FAILED，沿用原 `anki_note_id/exported_to_anki`。进程中断留下 EXPORTING 时，用户可再次导出确认结果；不是后台自动重试队列。历史 `exported_to_anki` 表示曾成功，最近一次结果以 `anki_export_status` 为准。

应用为前端将 Long 序列化为字符串，AnkiConnect `notesInfo` 要求数字 ID，因此适配器调用使用显式 LongNode；外部协议不能继承前端精度兼容格式。

## 运行配置

`study-agent.anki` 配置 endpoint（默认 `http://127.0.0.1:8765`）、timeout（10s）、model-name、deck-prefix。Docker eval 配置使用 `http://host.docker.internal:8765`。Anki 与 AnkiConnect 需运行在可连接的本机；接口返回非 200、error 或连接失败均明确报错。不自动安装插件、更改其监听设置或打开 AnkiWeb 同步。

当前接入针对本机未启用 API key 的 AnkiConnect；其他部署需要匹配其鉴权配置，不将鉴权失败视为成功。API 面向既定本地单机用户场景，未扩展为多用户 Anki 账户绑定系统。

## 验收方式

`scripts/verify-anki-export.py --run-dir .eval/runs/m7-anki-v1` 使用独立编写的三张数据结构卡及 SQL 来源 fixture，不声称这批卡由模型生成或由上传流水线产生。后端临时指向本机 8766 测试端点：先保持端口关闭验证失败落库，再启动仅监听回环的转发代理。代理把创建请求提交到真实 8765 Anki，收到成功后丢弃一次响应，随后通过重试、并发导出、再次导出验证唯一笔记。

脚本保存每次 API 返回、代理动作、MySQL 状态、Anki notesInfo 与 cardsInfo 的正反面渲染结果；只查询本次稳定标识对应的笔记。调用 guiDeckReview/guiCurrentCard/guiShowAnswer 验证实际进入本次牌组的复习界面，最后回到牌组列表，不提交评分。该证据不是桌面截图。

## 实际结果（2026-09-09）

- 本机 AnkiConnect API v6 已运行，Docker 后端可直连；没有安装或改动插件配置。
- 三张卡分别导入为笔记 `1788915150700 / 1788915151299 / 1788915151854`，每条笔记生成一张可复习卡。关闭测试端点后失败落库，真实创建后丢弃响应、重试恢复、两路并发及重复导出均通过；每个稳定标识仍只有一条笔记。跨用户导出拒绝。
- `guiDeckReview` 进入本次牌组，`guiCurrentCard` 确认当前卡属于本次导入，`guiShowAnswer` 返回成功；未提交评分，最后回到牌组列表。
- 浏览器恢复会话 `6100000805808704500`，验证单卡失败不影响另两卡、刷新后用会话编号恢复失败状态、恢复直连后的键盘重试，以及源文显示、Escape/焦点返回。390×844 下根文档 client/scroll 均380，无横向溢出。页面刷新仍需手动进入学习并恢复会话，不宣称自动恢复会话。
- 前端恢复连接后再次查询：首卡仍对应 `1788915150700`，总尝试6次；另外两卡2/1次。后台正常 endpoint 已恢复8765，临时8766代理已停止。
- 后端定向11项通过；首轮全量215项中1项字段契约未同步，修正后全量215项0 failure/error、3项既有跳过。前端37项、构建/typecheck与严格审计通过。真实本机验收前后模型尝试394→394。

原始本机记录：`.eval/runs/m7-anki-v1/`；可跟踪证据：[API/Anki验收](../evidence/m7/anki-export-v1.json)、[故障代理动作](../evidence/m7/anki-fault-events-v1.json)、[浏览器验收](../evidence/m7/anki-ui-v1.json)、[运行版本](../evidence/m7/anki-run-manifest-v1.json)。JAR SHA256 `c24acb4b712114a65234e8c2c7ac8b618cdc1fa0d732f7b83cceb551822f7982`。

这批证明已持久化卡片到实际 Anki 的导出链路；M5 自然模型生成三卡的新增链路仍需单独验收，不把 SQL fixture 算成模型生成证据。
