# M4 父块对照的测量修正

## 真实发现与范围

冻结配置的80题四模式请求全部成功（320次HTTP），但5题在独立RRF/PARENT请求中出现子块排名差异：algorithms-03-q5、algorithms-05-q3、algorithms-05-q4、compiler-insufficient-03、compiler-insufficient-04。原始一致性断言失败，日志保留在 `.eval/validation-search.log`；四模式原始请求与独立评分保留在 `.eval/runs/validation-selected-v1/`。

原服务的RRF/PARENT共用融合代码，但每次HTTP仍独立调用query embedding和ES。观察不足以区分provider向量变化、ANN或排序变化；未把原因断言为其中之一。对照需要控制的是实际候选结果，不能仅假设两次调用一致。

## 修正

- eval profile提供 `POST /api/eval/knowledge-bases/{id}/context-comparison`，请求为query/topK，服务端先校验知识库归属。
- `KnowledgeRetrievalService.compareContexts` 调用一次原有RRF搜索，再用同一份rankedChildren构建子块和父块上下文。两种view复用原生产预算、父块去重和来源逻辑；没有调整检索参数或排序算法。
- `scripts/run-parent-comparison.py` 保存80条真实HTTP响应到pairs.jsonl，另外导出160条view到responses.jsonl供统一评分与回答实验。160条view不是160次请求，二者共享原始trace。
- 真实80题验证rankedChildren（含顺序/分数）一致，且embedding账本每个trace恰好一次QUERY调用。两种view的正文均不超过4096本地token。
- Java定向回归13项通过，Python评分/日志回归4项通过，独立打包及真实入口通过。新回归验证一次检索、共享排名、分别应用预算；既有search行为继续通过原测试。

同时修正JSONL读取：原始资料含307处Unicode行分隔字符，Python `splitlines()` 会将其当成记录分隔并破坏合法JSON字符串。新增 `eval_jsonl.read_jsonl` 只按物理换行读取，保留原始文本；U+2028/U+0085/U+2029测试通过，没有清洗或改写已有响应。

## 当前结果与后续

60道可回答验证题的独立检索：BM25 Hit@5为42/60，VECTOR与RRF均55/60。RRF相对BM25提高21.67个百分点，但与纯向量持平；算法资料多为英文、提问主要中文，需保留这一解释边界。

共享排名下，子块上下文完整标注证据命中55/60，父块54/60：算法18→14，OS19→20，编译原理18→20。父块在4096正文预算下可能占用更多单块空间，使其它证据未被纳入；不能概括为所有课程均提升。

受控回答实验通过eval模型入口调用同一ObservedModel，输入仅含生产返回的上下文、来源与问题。随后独立调用同型号模型评审正确性、资料支持和引用支持；API/账本均计数，所有失败尝试保留。这是RAG组件对照，不是完整学习Agent E2E。模型评审存在同源偏差，不能称为人工准确率。

已用一题可回答、一题资料不足分别运行两种view的生成与评审，共8次模型调用，四个结果均通过，并由当前助手核对。全量80题两种view的生成/评审进行中，完成后另交正式报告。配置与题目没有因验证结果改变。
