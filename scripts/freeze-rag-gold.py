"""Reproduce the reviewed gold set before any retrieval-score inspection."""
import hashlib
import json
import random
import re
import unicodedata
from collections import Counter
from pathlib import Path


def norm(value):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value)).casefold()


def main():
    manifest = json.loads(Path("eval/rag/corpus-v1.json").read_text(encoding="utf-8"))
    sources = {s["id"]: s for s in manifest["files"]}
    texts = {sid: " ".join(Path(f".eval/corpus-production/{sid}.txt").read_text(encoding="utf-8").split()) for sid in sources}
    rows, changes = {}, []
    for sid, source in sources.items():
        draft = json.loads(Path(f".eval/runs/gold-draft-v2/{sid}.draft.json").read_text(encoding="utf-8"))
        for i, q in enumerate(draft["questions"], 1):
            key = f"{sid}-q{i}"
            rows[key] = dict(id=key, course=source["course"], sourceId=sid, question=q["question"],
                             expectedAnswer=q["answer"], quote=q["quote"], answerable=True,
                             type=q["type"], wording=q["wording"])

    def edit(key, reason, question=None, answer=None, quote=None):
        row = rows[key]
        before = dict(row)
        if question is not None:
            row["question"] = question
        if answer is not None:
            row["expectedAnswer"] = answer
        if quote is not None:
            row["quote"] = quote
        changes.append(dict(id=key, reason=reason, before=before, after=dict(row)))

    def span(sid, start, end):
        text = texts[sid]
        a = text.index(start)
        b = text.index(end, a) + len(end)
        return text[a:b]

    edit("algorithms-01-q2", "Scope the theorem to the lecture model, not all matching variants.",
         question="在课件定义的稳定匹配模型下，Gale-Shapley算法对所有实例有什么保证？",
         answer="在该模型下，对所有问题实例都能找到稳定匹配。")
    tight = rows["algorithms-02-q4"]["quote"]
    edit("algorithms-02-q4", "Remove a quiz option and unrelated examples from the evidence.",
         question="渐近紧确界 Θ(g(n)) 的形式化定义是什么？",
         answer="存在常数 c1>0、c2>0 和 n0≥0，使所有 n≥n0 满足 0≤c1·g(n)≤f(n)≤c2·g(n)。",
         quote=tight[:tight.index("𝑛0.") + len("𝑛0.")])
    edit("algorithms-04-q4", "Replace an unmarked quiz option with the explicit optimality theorem.",
         question="课件在最小化最大延迟的调度问题中，证明哪种截止时间排序策略是最优的？",
         answer="最早截止时间优先（earliest-deadline-first）调度。",
         quote=span("algorithms-04", "Theorem. The earliest-deadline-first", "is optimal."))
    edit("algorithms-06-q1", "Remove loosely stated ratios and use the readable sequence definition.",
         question="课件引例中的费氏数列从哪两个数开始，后续项怎样产生？",
         answer="从0、1开始，之后每项等于前两项之和。",
         quote=span("algorithms-06", "费氏数列是由0", "144......。"))
    edit("algorithms-06-q3", "Replace a damaged formula with a readable problem definition.",
         question="矩阵链相乘问题的最优值和最优解分别指什么？",
         answer="最优值是所需的最小乘法次数；最优解是达到该次数的矩阵相乘结合方式。",
         quote=span("algorithms-06", "给定n个连乘", "结合方式相乘(最优解)的？"))
    edit("algorithms-06-q4", "Replace a damaged recurrence with readable constraints and objective.",
         question="0-1背包问题要在什么约束下优化什么目标，为什么叫0-1？",
         answer="总重量不超过背包容量，选择物品使总价值最大；每件物品只有装入和不装入两种选择。",
         quote=span("algorithms-06", "给定n个物品", "这两种状态分别用1 和0 表示。"))
    edit("algorithms-06-q5", "Replace a damaged recurrence with the readable LCS objective.",
         question="最长公共子序列问题要求返回的最优值和最优解分别是什么？",
         answer="最优值是两个字符串最长公共子序列的长度，最优解是对应的子序列。",
         quote=span("algorithms-06", "给定两个定义在字符集", "对应的子序列(最优解) 。"))
    edit("os-01-q1", "The lecture uses the single-processor concurrency model; qualify that scope.",
         question="按课件的单处理器并发模型，并发与多任务并行在微观和宏观执行上有什么区别？")
    edit("os-01-q2", "Remove the near-verbatim numeric answer clues from the question.",
         question="课件用两个作业的顺序执行与并发执行对比，说明并发对CPU和设备利用率有什么意义？")
    edit("os-01-q3", "Keep only the necessary PCB definition and ownership evidence.",
         quote=span("os-01", "Process Control Block (PCB) Data structure", "user can’t modify it directly"))
    edit("os-02-q1", "Replace a task_struct question duplicated in os-01.",
         question="进程切换时，对当前进程和下一进程的执行上下文分别做什么操作？",
         answer="保存当前进程在PCB中的执行上下文（CPU状态），恢复下一个进程的执行上下文。",
         quote=span("os-02", "保存当前进程在PCB中的执行上下文", "恢复下一个进程的执行上下文"))
    edit("os-03-q1", "Buddy powers lost superscript structure; replace with readable paging arithmetic.",
         question="分页后，如何根据逻辑地址address和页面大小page_size计算页号与页内偏移？",
         answer="页号为address除以page_size的整数商，页内偏移为address % page_size。",
         quote=span("os-03", "分页后进程的逻辑地址由两部分构成", "address % page_size"))
    edit("os-03-q4", "Remove a second broad claim and diagram noise from a mapping question.",
         question="分页地址转换中，页表把哪一种编号转换成哪一种编号？",
         answer="将逻辑页号转换为物理帧号。", quote="页表完成逻辑页号到物理帧号的转换")
    edit("os-03-q5", "Do not generalize a particular file-backed page-fault illustration to all faults.",
         question="课件的缺页处理示意中，第3步通过什么查找该页数据在外存中的位置？",
         answer="通过maps查找。", quote="通过maps查找该页数据在外存中的位置")
    edit("os-04-q1", "A critical section does not itself contain entry/exit/remainder sections.",
         question="临界区（critical section）的定义是什么？",
         answer="进程中访问临界资源的一段需要互斥执行的代码。",
         quote="进程中访问临界资源的一段需要互斥执行的代码")
    edit("os-04-q2", "Exclude repeated slides; retain the initial complete list.",
         question="课件列出了哪些临界区访问规则，并把哪一项标为可选？",
         answer="忙则等待、有限等待、空闲则入，以及标为可选的让权等待。",
         quote=rows["os-04-q2"]["quote"].split("章节5：")[0].strip())
    edit("os-04-q5", "Qualify the signed semaphore implementation shown in the lecture.",
         question="在课件给出的有符号计数信号量实现中，P和V怎样修改sem，何时等待或唤醒？")
    edit("os-05-q1", "Answer only roles explicitly stated by the selected span.",
         answer="mutex用于互斥访问；fullBuffers和emptyBuffers用于条件同步。")
    edit("os-05-q2", "Keep only the reversed-order program; answer is a supported execution inference.",
         quote=rows["os-05-q2"]["quote"].split("章节5：进程间通信与并发控制 ", 1)[1])
    edit("os-05-q4", "Avoid entrenching an ambiguous Hansen/Mesa label in the source; compare stated behaviors.",
         question="课件比较了哪两种条件变量通知语义，它们是否需要重新检查条件？",
         answer="一种通知只是提示，唤醒后需重新检查条件；另一种通知同时让出管程，使被唤醒者能在条件成立时继续。")
    edit("os-05-q5", "Ask about a concrete possible deadlock, not an unsupported additional starvation claim.",
         question="哲学家都先拿左叉再拿右叉的代码中，若所有人同时拿到左叉，会出现什么等待局面？",
         answer="每人都持有左叉并等待被邻居持有的右叉，形成循环等待，可能发生死锁。")
    edit("os-06-q2", "Separate the four necessary conditions and avoid conflating mutual exclusion with no preemption.",
         answer="互斥、持有并等待、不可抢占、循环等待；发生死锁必须同时具备这些条件。")
    edit("os-06-q3", "Prevention need only break at least one necessary condition.",
         answer="对资源请求和分配施加限制，破坏至少一个死锁必要条件，从而防止死锁。",
         quote=span("os-06", "Dead lock prevention OS defines", "The constraint rules will destroy the conditions of dead lock"))
    edit("os-06-q4", "Use the common comparison slide and remove value judgments and excessive detail.",
         answer="预防事先约束资源请求和分配，破坏死锁必要条件；避免分析当前资源分配状态，只进行安全的分配。",
         quote=span("os-06", "Dead lock prevention OS defines", "try to allocate resource in a safe way"))
    edit("os-06-q5", "Remove unsupported request-matrix details; the quote supports the safety-sequence criterion.",
         answer="记录最大资源需求和当前分配，模拟分配后检查是否存在让所有进程正常结束的安全序列；存在才允许分配。")
    edit("compiler-01-q4", "Keep the source's particular grammar-based compression application scope.",
         question="课件举出的基于文法的数据压缩方案，压缩和解压分别做什么？",
         quote=span("compiler-01", "数据压缩 压缩", "文法推导出唯一串初始数据"))
    edit("compiler-02-q3", "Replace the duplicated four-component grammar question.",
         question="设计CFG时，一个语法概念及其组成模式分别放在产生式哪一边？",
         answer="语法概念放在产生式左部，它的组成模式放在右部，并用名字表示语法概念和单词。",
         quote=span("compiler-02", "将语法概念放在产生式左部", "都是用名字替换掉语法概念和单词——"))
    edit("compiler-03-q1", "Restrict to readable combination rules instead of a damaged epsilon glyph.",
         question="已知正规式r和s，(r)|(s)、(r)(s)和(r)*分别表示哪种语言运算？",
         answer="分别表示语言的并、连接和Kleene闭包（零次或多次连接）。")
    edit("compiler-03-q3", "Use the explicit repetition wording rather than uncertain glyph restoration.",
         question="正则表达式中的+和?分别允许子表达式出现多少次？",
         answer="+允许一次或多次，?允许零次或一次。")
    edit("compiler-04-q1", "Remove examples whose grammar is absent from the selected evidence.",
         question="LR分析中活前缀（viable prefix）的定义是什么？",
         answer="最右句型的前缀，且不越过其句柄的右端。",
         quote="活前缀 最右句型的前缀，不超过唯一句柄")
    edit("compiler-04-q3", "Remove unquoted table-example states; retain the explicit algorithm.",
         question="LR分析算法根据什么查action表，移进和归约时分别怎样更新栈？",
         answer="用栈顶状态s和当前输入符号a查action[s,a]。移进时压入a和目标状态并前移输入；归约A→b时弹出2|b|个栈符号，再压入A和goto[新栈顶状态,A]。")
    edit("compiler-06-q3", "Correct the generated algebraic explanation: reuse unchanged operands, not a=b+c implying a-d=b.",
         question="在课件代码 a:=b+c; b:=a-d; c:=b+c; d:=a-d 中，最后一句为什么可替换为d:=b？",
         answer="第二句已将a-d的值存入b，此后到最后一句前a和d均未改变，因此可复用b保存的同一表达式结果。")

    # Fixed source-stratified split. No retrieval results are consulted here.
    rng = random.Random(20260909)
    dev = set()
    for course in ("algorithms", "os"):
        for i, sid in enumerate(s for s in sources if sources[s]["course"] == course):
            candidates = [key for key, row in rows.items() if row["sourceId"] == sid]
            dev.update(rng.sample(candidates, 2 if i < 4 else 1))
    result = []
    for row in rows.values():
        row["split"] = "dev" if row["id"] in dev else "validation"
        quote = row.pop("quote")
        assert norm(quote) and norm(quote) in norm(texts[row["sourceId"]]), row["id"]
        row["evidence"] = [dict(sourceId=sid, sourceSha256=sources[sid]["sha256"], quote=quote)
                           for sid, text in texts.items()
                           if sources[sid]["course"] == row["course"] and norm(quote) in norm(text)]
        row["review"] = "assistant-reviewed-v1"
        result.append(row)
    negatives = [json.loads(line) for line in Path("eval/rag/insufficient-draft-v1.jsonl").read_text(encoding="utf-8").splitlines()]
    for row in negatives:
        row.update(split="validation", answerable=False, evidence=[], review="assistant-reviewed-v1")
        result.append(row)
    assert len(result) == 100 and sum(q["answerable"] for q in result) == 80
    assert sum(q["split"] == "dev" for q in result) == 20
    assert len({norm(q["question"]) for q in result}) == 100
    assert not any(q["course"] == "compiler" and q["split"] == "dev" for q in result)
    data = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in result)
    target = Path("eval/rag/gold-v1.jsonl")
    if target.exists() and target.read_text(encoding="utf-8") != data:
        raise RuntimeError("Frozen gold differs: create a new version and preserve the old one")
    target.write_text(data, encoding="utf-8")
    metadata = dict(version="gold-v1", sha256=hashlib.sha256(data.encode()).hexdigest(),
                    sha256Encoding="UTF-8 text with LF line endings; run manifests separately hash actual file bytes",
                    fileSha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                    splitSeed=20260909, questions=100, answerable=80, insufficient=20,
                    dev=20, validation=80, heldOutCourse="compiler", reviewChanges=len(changes),
                    provenance="DeepSeek drafts; Codex per-item semantic review; deterministic literal-source checks; no human gold review",
                    metric="annotated-evidence Hit@5 and MRR@10; incomplete semantic relevance labels",
                    limitations=["Readable text subset; damaged formula/OCR questions excluded before scoring",
                                 "Assistant-generated and reviewed; no independent human labels",
                                 "Source-grounded mostly single-span questions; not a universal RAG benchmark",
                                 "Negatives mix missing subject content, environment facts and course logistics"],
                    counts=dict(Counter(f"{q['course']}/{q['split']}/{q['answerable']}" for q in result)))
    Path("eval/rag/gold-v1.manifest.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    Path("eval/rag/gold-v1.review.json").write_text(json.dumps(changes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metadata, ensure_ascii=False))


if __name__ == "__main__":
    main()
