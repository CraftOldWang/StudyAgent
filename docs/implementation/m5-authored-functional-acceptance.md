# 自编资料功能验收准备

真实课程→DeepSeek的自动审批拒绝继续有效。为独立检查实现，另建完全自编虚构课程`eval/synthetic-learning/`，固定“键与作用域、TTL、版本、淘汰、重复请求”五个单元及四道习题。它不含下载课程，不替代两门真实课程的正式压缩实验。

## 已完成与当前阻塞

- M8实现及真实入库/检索/恢复已提交`8c5cfa9`。
- 已建立独立`eval-synthetic-learning`索引和新知识库`2097508144282923009`，仅上传`lesson.md`、`exercises.md`，原资料/转写不在该索引。
- 上传成功，最初停在EMBEDDING，错误为百炼域名`UnknownHostException`；后续重投又出现对象存储域名解析失败。失败原样保留于`.eval/runs/authored-learning-v1`及调用账本。
- Docker Desktop状态为running，但容器查询/执行最终返回管理API500；WSL只读诊断观察load average `125.26 84.89 40.16`，进一步诊断出现`Wsl/Service/0x8007274c`。不推断根因是某个具体服务，也不把它当业务代码失败。
- 已停止本轮ASR worker释放内存，成功缓存保留；未停止其它项目服务。当前ASR 8767需要按M8文档重新启动才能转写新资料。
- 尚未发起这组自编材料的规划或学习模型调用。新增两个脚本仅完成Python语法检查，不能标记真实验收通过。

## 恢复后的执行顺序

先确认Docker管理接口、项目依赖及DNS恢复，核对物理索引仍为`eval-synthetic-learning`。检查已保存两个文档的真实阶段；按正常重试接口恢复原文档，不新建重复资料库。`prepare`会复用已有文档ID并核对源文件哈希；已失败文档需要先明确恢复，脚本不会静默替换它们。

```powershell
.eval/python-env/Scripts/python.exe scripts/run-authored-learning-smoke.py prepare --run-dir .eval/runs/authored-learning-v1
.eval/python-env/Scripts/python.exe scripts/run-authored-learning-smoke.py plan --run-dir .eval/runs/authored-learning-v1
.eval/python-env/Scripts/python.exe scripts/run-authored-learning-smoke.py session --run-dir .eval/runs/authored-learning-v1
```

`prepare`发送自编文本至百炼embedding；`plan`发送该隔离资料至已配置学习模型，必须单独通过执行审批，不能据“自编”标签自动推定审批成功。`session`从成功计划创建会话，沿用应用幂等关系。会话编号写入`state.json`，后续脚本用该编号和知识库编号执行。

`scripts/run-learning-conversation-smoke.py`接受`--session-id`、`--knowledge-base-id`、`--run-dir`；先`--max-points 1 --disconnect-first-text`，验证真实首段文本后断开SSE，服务端原请求继续完成。每点脚本固定为讲解→边界追问→五题→不完整答案澄清→完整五题评分→三卡完成。固定提交A答案用于验证评分与反馈，不追求满分。

脚本先保存requestId，再发送；断线/退出后根据原请求查询，只有显式`--retry-failed`才重试失败请求；不换ID掩盖失败。逐步检查数据库经API公开的知识点状态、五题未提前暴露答案、三卡及实际来源API、已完成请求幂等返回。`--max-points 5`可从同一检查点继续完成其余知识点。原响应与SSE片段保留在忽略的运行目录。

该脚本验的是功能与来源，不能输出学习效果或压缩收益结论。正式压缩实验仍需两门真实课程的冻结计划/用户脚本、A/B/C三策略各三次交错执行、包含摘要和重试的完整usage、事实/纠错/引用质量核对；上述准备不能代替这些验收。
