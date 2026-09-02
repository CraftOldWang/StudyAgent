# D-Q13：音视频摄入与 ASR

> 状态：READY_FOR_DECISION  
> 属于 ingest provider 选择，不应改变上层 transcript 契约

## 统一输出契约应先于 Provider

建议所有 ASR adapter 输出：

```text
Transcript
  source_id
  language
  duration_ms
  provider/model/version
  segments[]

TranscriptSegment
  start_ms / end_ms
  text
  speaker
  confidence
  words[] optional
```

下游 chunk、引用和播放跳转只能依赖该契约，不依赖 provider 原始 JSON。

## 方案

### A. 阿里云 DashScope / Paraformer 云 API

优点：中文与国内网络环境适配；官方支持长文件、句/词时间戳和说话人能力；无需 GPU 运维。

缺点：音频出域、持续费用、provider 依赖；需要异步 job、轮询/回调和失败恢复。

### B. OpenAI Audio API

优点：中英混合、word/segment timestamp、自动 chunking 与 diarization API 较完整。

缺点：音频出域、网络与成本；国内可用性需要实际验证。

### C. 本地 Whisper / faster-whisper

优点：离线、隐私可控；faster-whisper 提供 VAD、batch、word timestamp，部署灵活。

缺点：GPU/CPU、模型下载、并发和长音频切片由项目负责；中文课堂术语未必是最佳。

### D. 本地 FunASR / Paraformer

优点：中文生态，官方仓库包含 ASR、VAD、标点、speaker diarization 与长音频组件。

缺点：模型和 GPU 运维、Java/Python 服务边界、版本与资源占用需要验证。

### E. Provider 抽象 + 先云后本地

先用云 API 验证产品链路和数据集；统一契约稳定后，用同一数据集比较本地模型。

这是降低前期工程投入的演进方案，但不是自动 fallback；provider 失败必须明确。

## 评测维度

- 中文 CER、英文 WER；
- 课程术语、数字、代码和缩写准确率；
- 中英 code-switch；
- speaker DER；
- segment boundary error、timestamp MAE；
- VAD false positive/negative；
- 长音频失败率、RTF、峰值内存/显存和单位音频成本。

测试集应覆盖单教师、师生问答、多人重叠、噪声、远场、1–2 小时长音频。

## 项目适配与推荐

推荐 E 作为选择流程，而不是最终 provider：

1. 先固定 Transcript/Segment 契约和人工留出集；
2. 若追求最快中文 MVP，先验证 DashScope；
3. 若隐私或长期成本成为硬约束，用同一数据集比较 FunASR 与 faster-whisper；
4. 最终只配置一个明确 provider，不做静默自动切换。

## 需要用户拍板

- 云 API、本地模型或先云后本地；
- 首版是否要求 speaker diarization 和 word timestamp；
- 允许的音频出域/隐私边界；
- 是否接受独立 Python ASR worker；
- 单文件时长、大小、并发和失败重试上限。

## 一手来源

- [阿里云 ASR 模型](https://help.aliyun.com/zh/model-studio/asr-model)
- [阿里云非实时语音识别](https://help.aliyun.com/en/model-studio/non-realtime-speech-recognition-user-guide)
- [OpenAI Audio API](https://platform.openai.com/docs/api-reference/audio)
- [OpenAI Whisper](https://github.com/openai/whisper)
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper)
- [FunASR](https://github.com/modelscope/FunASR)
