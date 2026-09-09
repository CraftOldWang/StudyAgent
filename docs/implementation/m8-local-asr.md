# M8 · 本机音视频转写

## 方案与边界

采用本机 faster-whisper 1.2.1 / CTranslate2 4.4.0，Whisper small 固定版本 `536b0662742c02347bc0e980a01041f333bce120`，CPU int8、4线程。选择依据：[faster-whisper 官方说明](https://github.com/SYSTRAN/faster-whisper)、[模型仓库](https://huggingface.co/Systran/faster-whisper-small)、[CTranslate2 安装要求](https://opennmt.net/CTranslate2/installation.html)。模型从公开仓库下载，转写仅加载本地文件；embedding/学习仍遵循各自 provider 与授权边界，本机ASR不授权向云端发送转写文本。

支持MP4/M4A/MP3/WAV，不做说话人识别、实时字幕、时间轴播放器或公网媒体地址。原资料不修改；首个烟测片段来自 `编译原理/课件/第10章/10-2.m4a` 的60–120秒，原录音797.836秒。视频候选 `数据结构/20221213复习课.mp4` 为3926.821秒。“其他与StudyPilot项目无关内容暂时放在这里”目录不作为课程样本。

## 服务与恢复

`scripts/local-asr-worker.py` 仅监听127.0.0.1:8767；GET `/health` 返回已加载模型配置，POST `/transcribe` 接收已知长度的媒体字节及SHA-256。独立核验内容哈希，串行推理；按输入哈希及模型/参数指纹缓存成功结果，响应丢失后重试可复用。`local_files_only=True` 禁止推理时下载，缓存目录保存尝试账本、时间片段、文本和耗时；临时媒体文件在请求结束清理。

`MediaTranscriptionService` 从RustFS取原媒体，上传本机worker，等待时按文档租约续租，不持有数据库长事务。失败、无语音、版本/内容哈希不符均报错。原始JSON保存至RustFS，V15的 `asr_result_key/asr_metadata_json` 关联原始结果及模型、语言、音频/VAD时长、推理耗时。配置变更必须同步新的processor-version，不能将旧转写当新配置结果复用。

流水线：STORED → TRANSCRIBING → TRANSCRIBED → PARSED → CHUNKING → EMBEDDING → INDEXING → INDEXED。段落正文带 `[HH:MM:SS.mmm–HH:MM:SS.mmm]`，复用统一切块、embedding产物和逐项索引确认。chunk关联原音视频文档及字符区间，时间标识保留在正文，不冒充课件页码。

失败记录具体阶段，重启可恢复过期执行。规范文本已保存则直接复用；只保存原始ASR产物时先读取它；后端还未登记成功结果时由worker内容缓存避免重复推理。

## 启动

隔离Python环境安装 `scripts/requirements-asr.txt`。本地模型目录需有config.json、tokenizer.json、vocabulary.txt、model.bin，并记录固定版本与哈希。

```powershell
.eval/python-env/Scripts/python.exe scripts/local-asr-worker.py --model-dir .eval/asr-models/faster-whisper-small --cache-dir .eval/asr-cache
```

后台启动隐藏窗口并保存PID/日志。Docker eval 指向 `http://host.docker.internal:8767/transcribe`；原生后端默认127.0.0.1。配置为 `study-agent.asr.endpoint/timeout/processor-version`，默认等待上限2小时。worker未运行时媒体处理明确失败；原文本解析路径不依赖worker。

## 验收状态

后端17项定向测试与222项完整测试通过（219通过、3项既有跳过），前端37项测试、构建与严格UI审计通过。真实音视频的转写、入库、检索及后端终止恢复已实测；学习问答尚待实测，不能据此关闭M8。用户已明确授权课程资料外传至DeepSeek，当前先恢复Docker运行环境。本地ASR尝试另计，报告音频时长、处理耗时和RTF，不虚构云端token usage。

Windows兼容性烟测：4.8.2在本机加载模型时access violation，固定AVX2仍失败；4.4.0补齐setuptools80.9.0后可加载，但ONNX Runtime1.29.0自身导入崩溃。固定ONNX1.20.1并补齐其依赖后，60秒录音成功转写16段、约16.047秒，RTF约0.267。保留全部失败日志，不推断未经定位的底层根因。术语可辨认，但存在同音字错误；未人工逐字标注，不报告CER/准确率。

### 真实资料与恢复结果

| 样本 | 实际结果 | 范围 |
|---|---|---|
| 完整 `10-2.m4a`，19,766,324字节 | 797.838秒音频，129.359秒ASR，253段，RTF 0.162；17个parent/child合计，13次文档embedding、9126 tokens | 模型已加载、CPU int8/4线程；不是多次性能基准 |
| 复习课MP4的600–660秒片段 | 60秒视频，15.922秒ASR，20段，RTF 0.265；2个parent/child合计，1次文档embedding、717 tokens | 浏览器真实上传，观察转写中→可检索；时间戳相对片段起点 |
| 同一MP4的60–120秒片段 | 未识别到语音，明确FAILED，未保存转写/规范文本，无chunk | 保留首次失败，不把换样本后成功记作首次成功 |
| 自编无效WAV字节 | worker离线时连接失败，启动后重试为解码HTTP422；无转写/规范文本及chunk | 既有RocketMQ重投会增加尝试次数，观测12次，不声称仅两次 |

完整录音在01:45:12 UTC开始转写。数据库租约从01:50:11续至01:50:21后，01:45:34终止后端容器，01:45:35重启；worker独立完成并保存缓存，01:50:43租约恢复后命中缓存，01:51:00文档INDEXED。该输入账本恰好1次STARTED、1次SUCCEEDED、1次CACHE_HIT；流水线attempt=2。测试保留默认5分钟租约及30秒恢复扫描，不人为缩短等待。观测脚本最初因启动阶段HTTP502退出，修正后使用`--resume`继续观察同一文档，没有再次上传或终止容器。

生产API中，录音“直接支配者”定义在BM25和RRF均排第一；视频“希尔排序又叫什么排序”在BM25排第一。来源API核对内容与chunk归属一致，时间戳保留。浏览器普通检索也展示了原媒体文件名、格式化字符范围与转写正文；390px下文档client/scroll均380px。这里只是两条来源检索验收，不计算总体RAG命中率。无语音样本未参与成功指标；其既有MQ重投观测5次，后续计数可能增加。

文档embedding由已配置百炼执行，录音/视频分别13/1次，均成功且usage齐全；上述数据不包含另外的检索QUERY embedding。ASR/Anki不使用DeepSeek，本轮LLM账本仍394次。

### 复现与证据

恢复测试要求独立`eval-m8-asr`索引、已启动本地worker，以及可用的RustFS/MQ/embedding依赖：

```powershell
.eval/python-env/Scripts/python.exe scripts/verify-asr-recovery.py --file 'D:/Download/BDNetdisk_DL/编译原理/课件/第10章/10-2.m4a' --run-dir .eval/runs/new-asr-recovery
```

脚本会真实终止并重启测试后端，且转写文本进入配置的embedding provider；不调用学习模型。首次故障实验需要尚未被当前worker缓存的输入。中途观测退出时以同一参数加`--resume`恢复，不新建文档。脚本结果仅标记入库/检索完成，需另行核对来源与语义，不能直接当成学习通过。

版本化证据见`docs/evidence/m8/audio-recovery-v1.json`、`video-ingest-v1.json`、`embedding-usage-v1.json`、`invalid-media-v1.json`和`run-manifest-v1.json`。原转写及API完整正文仅保留于忽略的`.eval/runs/m8-*`和`.eval/asr-cache`；仓库记录输入、模型、JAR与原结果哈希及摘要指标。模型SHA256为`3e305921506d8872816023e4c273e75d2419fb89b24da97b4fe7bce14170d671`，实际依赖冻结于`.eval/m8-asr-python-freeze-compatible.txt`。
