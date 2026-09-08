"""Controlled RRF/parent answer comparison through counted backend model calls, not a full Agent E2E."""
import argparse
import hashlib
import json
import statistics
import sys
import time
from pathlib import Path

import requests
from eval_jsonl import read_jsonl


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--retrieval-run", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--modes", nargs="+", choices=["RRF", "PARENT"], default=["RRF", "PARENT"])
    parser.add_argument("--only", nargs="+", help="Smoke selected IDs; later omit to resume the identical full protocol")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    source = args.retrieval_run / "responses.jsonl"
    retrieval = {(r["questionId"], r["mode"]): r for r in read_jsonl(source)
                 if r.get("status") == 200 and r.get("response", {}).get("code") == 0}
    questions = [q for q in read_jsonl("eval/rag/gold-v1.jsonl") if q["split"] == "validation"]
    systems = {stage: Path(f"eval/rag/{name}.txt").read_text(encoding="utf-8") for stage, name in
               [("answer", "answer-system-v1"), ("judge", "answer-judge-system-v1")]}
    manifest = {"goldFileSha256": sha("eval/rag/gold-v1.jsonl"), "retrievalResponsesSha256": sha(source),
                "runnerSha256": sha(__file__), "modes": args.modes,
                "systemPromptHashes": {k: hashlib.sha256(v.encode()).hexdigest() for k, v in systems.items()},
                "protocol": "controlled API RAG component; shared backend ObservedModel; separate same-model judge calls",
                "limitations": "Same-family judge bias; assistant-reviewed reference answers; not independent human evaluation"}
    manifest_path = args.run_dir / "manifest.json"
    if manifest_path.exists():
        assert json.loads(manifest_path.read_text(encoding="utf-8")) == manifest, "Inputs changed; preserve this run"
    else:
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    session = requests.Session()
    session.headers["X-User-Id"] = "1"

    def completion(qid, mode, stage, payload):
        path = args.run_dir / f"{qid}-{mode}-{stage}.json"
        request = {"purpose": "rag-" + stage, "promptVersion": stage + "-v1", "systemPrompt": systems[stage],
                   "prompt": json.dumps(payload, ensure_ascii=False), "maxTokens": 1536 if stage == "answer" else 768}
        request_hash = hashlib.sha256(json.dumps(request, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        if path.exists():
            raw = json.loads(path.read_text(encoding="utf-8"))
            assert raw["requestHash"] == request_hash, "Resume request differs"
        else:
            raw = {"questionId": qid, "mode": mode, "stage": stage, "request": request, "requestHash": request_hash,
                   "startedAt": time.time()}
            start = time.perf_counter()
            try:
                response = session.post("http://localhost:8080/api/eval/completions", json=request, timeout=180)
                raw.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
            finally:
                raw["elapsedMillis"] = (time.perf_counter() - start) * 1000
                path.write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if raw.get("status") != 200 or raw.get("response", {}).get("code") != 0:
            raise RuntimeError("Recorded failed model request: inspect before explicitly retrying; it was not discarded")
        assert raw["traceId"]
        result = json.loads(raw["response"]["data"]["text"])
        return result, raw["traceId"]

    results = []
    for q in questions:
        if args.only and q["id"] not in args.only:
            continue
        for mode in args.modes:
            r = retrieval[q["id"], mode]
            contexts = [{"chunkId": h["chunkId"], "content": h["content"], "provenance": h["provenance"]}
                        for h in r["response"]["data"]["hits"]]
            answer, answer_trace = completion(q["id"], mode, "answer", {"question": q["question"], "contexts": contexts})
            assert type(answer["answerable"]) is bool and isinstance(answer["answer"], str) and answer["answer"].strip()
            assert isinstance(answer["citations"], list) and all(isinstance(cid, str) for cid in answer["citations"])
            citations_valid = set(answer["citations"]) <= {c["chunkId"] for c in contexts}
            judge, judge_trace = completion(q["id"], mode, "judge", {"question": q["question"], "expectedAnswerable": q["answerable"],
                "referenceAnswer": q["expectedAnswer"], "referenceEvidence": q["evidence"], "contexts": contexts, "candidate": answer})
            assert all(type(judge[key]) is bool for key in ["correct", "grounded", "citationSupport"])
            row = {"questionId": q["id"], "course": q["course"], "mode": mode, "expectedAnswerable": q["answerable"],
                   "answer": answer, "judge": judge, "citationsValid": citations_valid,
                   "searchTrace": r["traceId"], "answerTrace": answer_trace, "judgeTrace": judge_trace,
                   "accepted": judge["correct"] and judge["grounded"] and judge["citationSupport"] and citations_valid}
            results.append(row)
            print(json.dumps({"id": q["id"], "mode": mode, "accepted": row["accepted"]}), flush=True)
    if args.only:
        if not results:
            raise ValueError("No requested question IDs belong to validation")
        (args.run_dir / "smoke-judgments.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"smokeOnly": True, "completedPairs": len(results), "formalScoresProduced": False}))
        return
    report = {}
    for mode in args.modes:
        report[mode] = {}
        for course in sorted({q["course"] for q in questions}):
            rows = [r for r in results if r["mode"] == mode and r["course"] == course]
            positive = [r for r in rows if r["expectedAnswerable"]]
            negative = [r for r in rows if not r["expectedAnswerable"]]
            report[mode][course] = {"answerableCount": len(positive), "insufficientCount": len(negative),
                "acceptedAnswerRate": statistics.mean(r["accepted"] for r in positive),
                "correctAnswerRate": statistics.mean(r["judge"]["correct"] for r in positive),
                "groundedAnswerRate": statistics.mean(r["judge"]["grounded"] for r in positive),
                "correctRefusalRate": statistics.mean(r["accepted"] and not r["answer"]["answerable"] for r in negative),
                "unsupportedResponseRate": statistics.mean(not r["judge"]["grounded"] for r in negative)}
    (args.run_dir / "judgments.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.run_dir / "scores.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
