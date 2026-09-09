# StudyPilot

面向桌面浏览器演示：资料入库 → 参考课件和习题生成重点计划 → 讲解、答疑、测验 → 复习卡与 Anki 导出。

## IDEA / 本机开发启动（当前入口）

1. 启动 Docker Desktop，在根目录执行 `docker compose up -d mysql redis elasticsearch rustfs rocketmq-namesrv rocketmq-broker`。
2. 本机 hosts 已有 `127.0.0.1 rocketmq-broker`，供 Java 访问 MQ 返回的 broker 地址；新机器需配置同样映射。
3. IDEA 导入 Maven 项目，选 JDK 21，工作目录为项目根目录，直接运行 `StudyAgentApplication`。无需设置 active profile，默认 `local`；命令行也可用 `mvn spring-boot:run`。
4. `frontend` 中执行 `npm run dev`，访问 http://localhost:5173。

默认 local 配置复用现有 `study_agent_eval` 数据库和选定索引，不连接旧项目的 `study_agent`。旧库保留，不关闭 Flyway、不执行 repair。Windows 启动入口使用 `.eval/sockets` 保存 JDK 临时 socket，避开本机系统 TEMP 的 AF_UNIX 连接错误。

Java 在 Windows 运行，只有中间件在 Docker。不要同时启动下方历史 Docker 后端，以免争用 8080 和消息消费。IDEA 修改源码后重新运行应用即可，无需向容器同步项目。

## 之前的 Docker 验收启动方式（历史说明）

`eval` 是真实后端的配置名称，不是 mock。项目目前混合运行：

| 部分 | 运行位置 | 本机入口 |
|---|---|---|
| React / Vite | Windows Node | http://localhost:5173 |
| Spring Boot / AgentScope | Docker `study-agent-eval-app-1`，Java 21 JAR | http://localhost:8080 |
| MySQL / Redis / Elasticsearch | Docker | 3307 / 6380 / 9200 |
| RustFS | Docker | 9000（S3）/ 9001（管理页）|
| RocketMQ nameserver / broker | Docker | 9876 / 10911 |
| ASR / AnkiConnect | Windows，可选 | 8767 / 8765 |
| 真实流程和性能实验 | Windows Python，通过 HTTP 调后端 | `scripts/` |

以下适用于**这台已初始化的机器**，保留现有课程和索引。新机器还需准备密钥、数据库、模型及 Maven 缓存，当前不是完整的一键安装包。

先启动 Docker Desktop，在项目根目录执行：

```powershell
docker compose up -d mysql redis elasticsearch rustfs rocketmq-namesrv rocketmq-broker
.eval/python-env/Scripts/python.exe scripts/run-chunk-config.py --config eval/rag/config-validation-selected.json --deploy-only
```

第二条启动已有 JAR，生成选定索引的 Compose 覆盖配置，不重新导入课程或运行模型实验。只使用两个基础 Compose 文件会回到 `application-eval.yml` 的默认索引，所以统一从这个入口启动。

另开终端启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

依赖已安装且锁文件未变化时跳过 `npm ci`。若 `npm` 不在 PATH，本机可使用 `C:/Users/CraftOldW/AppData/Local/hermes/node/npm.cmd`。访问 http://localhost:5173；Vite 将 `/api` 代理到后端 8080。

**后端源码变化后**才重新打包，下面跳过测试；完成后重新运行上面的 `--deploy-only`：

```powershell
docker compose -f docker-compose.yml -f docker-compose.eval.yml run --rm --no-deps eval-app mvn -q -DskipTests clean package
```

密钥由根目录 `some_apiKey` 加载，不提交 Git。当前使用 `study_agent_eval` 数据库、Redis DB 1、`study-agent-eval` bucket 和 `eval-validation-structured-800-0` 索引。

新音视频转写需另开终端启动 worker；普通课件和已转写资料不依赖 worker 在线。Anki 导出需打开安装了 AnkiConnect 的 Anki。

```powershell
.eval/python-env/Scripts/python.exe scripts/local-asr-worker.py --model-dir .eval/asr-models/faster-whisper-small --cache-dir .eval/asr-cache
```

Canal、Kibana、RocketMQ Dashboard 不需要随核心演示启动。当前 `eval` 后端关闭 Canal，即使容器运行也不消费它。停止项目可用 `docker compose -f docker-compose.yml -f docker-compose.eval.yml stop`；前端终端按 Ctrl+C。保留数据卷和 `.eval` 中的会话、模型及原始实验数据。

## 必要文件的职责

Java 路径均相对 `src/main/java/com/studyagent/`：

| 路径 | 职责 |
|---|---|
| 根 `pom.xml`、`StudyAgentApplication.java` | 后端依赖与启动入口 |
| `src/main/resources/application*.yml`、`src/main/resources/db/migration/` | 配置与数据库结构；旧迁移仍是新建数据库的输入 |
| `ingest/` | 上传、解析/转写、切块、异步处理与恢复 |
| `rag/`、`algo/chunk/`、`algo/rrf/` | 索引、检索、来源读取、切块与融合 |
| `learning/`、`agent/` | 计划、知识点状态、Agent 工具、对话、摘要、trace |
| `review/` | 卡片与 Anki 导出 |
| `config/`、`identity/`、`common/`、`model/`、`mapper/` | 配置、用户范围、通用响应与持久化 |
| 根 `frontend/src/`、前端包/锁文件和 Vite/TS 配置 | 桌面页面、API、SSE、上传和构建 |
| 根 `docker-compose*.yml`、`docker/`、`.agentscope/workspace/skills/` | 部署配置、Agent skill 资源 |
| 根 `eval/`、必要 `scripts/`、最终实验结果 | 简历数据的样本、执行入口与统计依据 |
| 根 `src/test/`、前端 `*.test.*` | 回归测试，不属于应用运行路径 |

这是职责图，不表示目录中每个现存文件都必须保留。接口、组件和状态见 [架构导读](docs/implementation/architecture-walkthrough.md)。

## 清理候选

Goal 仍暂停，以下尚未执行代码删除：

- **旧流程：** 页面已用 `/plans` 和自然消息 SSE，`learningApi.ts` 仍有旧创建会话、讲解、出题、生成卡片包装。统一入口时同步清理旧 `LearningPlanService`、对应 Controller 方法、脚本和测试；同步 HTTP 消息接口仍可能被批测使用。
- **未接入功能：** `algo/fsrs/` 没有业务调用，以 Anki 导出作为当前演示范围时无需自建调度器。Canal 在当前配置关闭，可以与现有 MQ/阶段恢复方案一起收敛，再移除相关依赖、配置与容器定义。
- **实验脚本：** 日常只暴露入库、RAG 批测、完整学习、压缩对照、上传计时和用量汇总。旧造数、冻结、单次修复和审计脚本不作为每次开发步骤；删除前检查被保留脚本的 import 和子进程依赖。
- **测试：** 删除随旧功能失效的测试，以及没有实际收益的重复字段/常量检查；保留状态转换、重复写入、来源归属、检索算法、摘要恢复和 SSE 等能发现实际回归的测试。
- **文档：** 日常从本 README、架构导读和 `PROGRESS.md` 进入；最终指标链接原始结果，旧过程记录不再逐轮读取和扩写。`AGENTS.md` 决策条款仍由用户维护。
- **临时文件：** 旧构建产物、截图、日志可按需清理；`.eval` 同时保存模型缓存、会话状态和原始测量，不能整目录删除。其他工具的未跟踪目录也不等于废文件。

## Demo 验证方式

日常只验证受影响部分：文档检查差异；页面改动构建一次并看桌面效果；业务改动跑相关现有测试，再走一次受影响的 API 或页面流程。没有新改动或失败原因时，不重复全量验证。

后端测试通过 Docker 中的 Maven 运行，前端测试通过 Windows `npm test` 运行。真实实验脚本调用后端，可能消耗模型额度或改变演示数据，不作为每次提交的默认检查。

需要简历指标时才做对应对照实验，保留参数、原始结果、失败记录和汇总。先确认一组完整运行，再决定重复次数；不默认执行大矩阵、逐文件哈希、额外冻结和多层审计。上传去重的 SHA-256 是产品功能，继续保留。
