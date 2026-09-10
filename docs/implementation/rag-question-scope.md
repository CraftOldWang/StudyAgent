# RAG 人工出题资料范围

统计时间：2026-09-10。直接读取当前 Elasticsearch 检索索引；没有重新解析、向量化或运行模型。

当前索引：`eval-validation-structured-800-0`。仅列本次实验的三个课程资料库；其他同名历史实验库不在本范围。

建议从操作系统这 6 份课件准备 60 道真实检索问题。若更熟悉其他课程，也可使用下面对应范围；不要把未列入的课件算作可检索资料。

|课程|文件数|子块数|父块数|系统可检索文本|
|---|---:|---:|---:|---|
|操作系统|6|73|23|[打开文本](<D:/1Learningoutput/javabackend/StudyAgent/output/rag-question-scope/os.md>)|
|算法设计与分析|6|50|17|[打开文本](<D:/1Learningoutput/javabackend/StudyAgent/output/rag-question-scope/algorithms.md>)|
|编译原理|6|14|6|[打开文本](<D:/1Learningoutput/javabackend/StudyAgent/output/rag-question-scope/compiler.md>)|

## 操作系统

网页资料库名称：`Corpus corpus-validation-structured-800-0 os`；ID：`2097399583862374402`。

|原始课件|子块|父块|
|---|---:|---:|
|[3.1.OS_ProcessManagement-进程的上下文切换-2024.pptx](<D:/Download/BDNetdisk_DL/操作系统/课件/3.1.OS_ProcessManagement-进程的上下文切换-2024.pptx>)|17|5|
|[3.2.OS_ProcessManagement-进程的调度策略-2024.pptx](<D:/Download/BDNetdisk_DL/操作系统/课件/3.2.OS_ProcessManagement-进程的调度策略-2024.pptx>)|15|5|
|[4.2OS_MemoryManagement-分页机制-2024.pptx](<D:/Download/BDNetdisk_DL/操作系统/课件/4.2OS_MemoryManagement-分页机制-2024.pptx>)|15|4|
|[5.3OS_IPCandConcurrency-并发控制的方法-2024.pptx](<D:/Download/BDNetdisk_DL/操作系统/课件/5.3OS_IPCandConcurrency-并发控制的方法-2024.pptx>)|10|3|
|[5.4OS_IPCandConcurrency-并发控制的典型问题-2024.pptx](<D:/Download/BDNetdisk_DL/操作系统/课件/5.4OS_IPCandConcurrency-并发控制的典型问题-2024.pptx>)|11|4|
|[9.OS_Deadlock-死锁-2024.pptx](<D:/Download/BDNetdisk_DL/操作系统/课件/9.OS_Deadlock-死锁-2024.pptx>)|5|2|

## 算法设计与分析

网页资料库名称：`Corpus corpus-validation-structured-800-0 algorithms`；ID：`2097399516493463554`。

|原始课件|子块|父块|
|---|---:|---:|
|[Chap01.pdf](<D:/Download/BDNetdisk_DL/算法设计和分析/课件/Chap01.pdf>)|5|2|
|[Chap02.pdf](<D:/Download/BDNetdisk_DL/算法设计和分析/课件/Chap02.pdf>)|7|2|
|[Chap03.pdf](<D:/Download/BDNetdisk_DL/算法设计和分析/课件/Chap03.pdf>)|8|3|
|[Chap04-1.pdf](<D:/Download/BDNetdisk_DL/算法设计和分析/课件/Chap04-1.pdf>)|9|3|
|[Chap05.pdf](<D:/Download/BDNetdisk_DL/算法设计和分析/课件/Chap05.pdf>)|6|2|
|[Chapter-6 动态规划.pdf](<D:/Download/BDNetdisk_DL/算法设计和分析/课件/Chapter-6 动态规划.pdf>)|15|5|

## 编译原理

网页资料库名称：`Corpus corpus-validation-structured-800-0 compiler`；ID：`2097399730608488450`。

|原始课件|子块|父块|
|---|---:|---:|
|[2-编译器结构.pptx](<D:/Download/BDNetdisk_DL/编译原理/课件/第1章/2-编译器结构.pptx>)|2|1|
|[1-CFG.pptx](<D:/Download/BDNetdisk_DL/编译原理/课件/第2章/1-CFG.pptx>)|2|1|
|[3-正则表达式.pptx](<D:/Download/BDNetdisk_DL/编译原理/课件/第3章/3-正则表达式.pptx>)|2|1|
|[11-LR分析.pptx](<D:/Download/BDNetdisk_DL/编译原理/课件/第4章/11-LR分析.pptx>)|4|1|
|[1-三地址码.pptx](<D:/Download/BDNetdisk_DL/编译原理/课件/第8章/1-三地址码.pptx>)|2|1|
|[3-基本块.pptx](<D:/Download/BDNetdisk_DL/编译原理/课件/第9章/3-基本块.pptx>)|2|1|

## 如何写题

每题记录：自然提问、资料文件名与页码/幻灯片号、简短参考答案或关键证据。可以接受多处等价证据，无需自己标 chunk ID。先看原件出真实问题，再看可检索文本确认关键内容是否被解析。

60 道题可全部来自操作系统上述范围。按内容自然分配，不必每份恰好 10 题。关键词和自然语言描述作为标签，不预设检索方式胜负。

只有图片中有依据而解析文本缺失的问题，单独标记为解析缺失；不能直接归为向量检索或 RRF 失败。

## overlap 选择依据

当前子块 800 token / overlap 0，父块 2400 / overlap 0；结构优先切块，超长片段才按 token 切。旧的算法+OS共20题开发集上，800/0与800/80的平均Hit@5均95%，MRR@10分别0.7975与0.7892。因此当时选择重复文本更少的800/0；不是零重叠普遍最优的结论。
