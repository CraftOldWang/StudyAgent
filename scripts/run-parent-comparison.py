"""Compare two production context views from one shared retrieval; record raw HTTP plus derived view rows."""
import argparse
import hashlib
import importlib.util
import json
import statistics
import sys
import time
from pathlib import Path

import requests
from eval_jsonl import read_jsonl

spec = importlib.util.spec_from_file_location("scorer", Path(__file__).with_name("run-rag-evaluation.py"))
scorer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scorer)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--ingest-state", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    state = json.loads(args.ingest_state.read_text(encoding="utf-8"))
    config = json.loads(args.config.read_text(encoding="utf-8"))
    hashes = {str(d["documentId"]): d["sourceSha256"] for d in state["documents"].values()}
    questions = [q for q in read_jsonl("eval/rag/gold-v1.jsonl") if q["split"] == "validation"]
    manifest = {"goldSha256": sha("eval/rag/gold-v1.jsonl"), "config": config, "ingestStateSha256": sha(args.ingest_state),
                "jarSha256": sha("target/study-agent-0.0.1-SNAPSHOT.jar"), "runnerSha256": sha(__file__),
                "protocol": "One query embedding and one RRF retrieval per question; production service builds both context views",
                "topK": 10, "contextTextBudget": config["contextMaxTokens"]}
    path = args.run_dir / "manifest.json"
    if path.exists():
        assert json.loads(path.read_text(encoding="utf-8")) == manifest, "Resume inputs changed"
    else:
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    raw_path = args.run_dir / "pairs.jsonl"
    previous = read_jsonl(raw_path) if raw_path.exists() else []
    completed = {r["questionId"]: r for r in previous if r.get("status") == 200 and r.get("response", {}).get("code") == 0}
    http = requests.Session()
    http.headers["X-User-Id"] = "1"
    for q in questions:
        if q["id"] in completed:
            continue
        kb = str(state["knowledgeBases"][q["course"]])
        row = {"questionId": q["id"], "query": q["question"], "course": q["course"], "knowledgeBaseId": kb,
               "startedAt": time.time(), "path": f"/api/eval/knowledge-bases/{kb}/context-comparison"}
        started = time.perf_counter()
        try:
            response = http.post("http://localhost:8080" + row["path"], json={"query": q["question"], "topK": 10}, timeout=120)
            row.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
            response.raise_for_status()
            assert row["response"]["code"] == 0 and row["traceId"]
        finally:
            row["elapsedMillis"] = (time.perf_counter() - started) * 1000
            with raw_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
        completed[q["id"]] = row
        print(json.dumps({"id": q["id"], "status": row["status"]}), flush=True)
    views = []
    for q in questions:
        pair = completed[q["id"]]
        data = pair["response"]["data"]
        assert data["childContext"]["rankedChildren"] == data["parentContext"]["rankedChildren"]
        for mode, field in [("RRF", "childContext"), ("PARENT", "parentContext")]:
            value = data[field]
            assert value["mode"] == mode and value["contextTokens"] <= config["contextMaxTokens"]
            assert len({h["chunkId"] for h in value["hits"]}) == len(value["hits"])
            row = {"questionId": q["id"], "query": q["question"], "course": q["course"], "mode": mode,
                   "status": 200, "traceId": pair["traceId"], "derivedFrom": "pairs.jsonl:" + q["id"],
                   "response": {"code": 0, "data": value}}
            if q["answerable"]:
                row["score"] = scorer.score(value, q, hashes)
            views.append(row)
    # These are views of each raw server response, not additional HTTP calls or fabricated retrievals.
    (args.run_dir / "responses.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in views), encoding="utf-8")
    ledger = read_jsonl(".eval/embedding-calls.jsonl")
    for pair in completed.values():
        calls = [e for e in ledger if e["status"] == "STARTED" and e.get("traceId") == pair["traceId"] and e["purpose"] == "QUERY"]
        assert len(calls) == 1, "Comparison did not use exactly one shared query embedding"
    report = {}
    for mode in ["RRF", "PARENT"]:
        report[mode] = {}
        for course in ["algorithms", "os", "compiler"]:
            values = [r["score"] for r in views if r["mode"] == mode and r["course"] == course and "score" in r]
            report[mode][course] = {"n": len(values), "hitAt5": statistics.mean(r["hitAt5"] for r in values),
                "mrrAt10": statistics.mean(r["reciprocalRankAt10"] for r in values),
                "contextEvidenceHit": statistics.mean(r["contextEvidenceHit"] for r in values),
                "meanContextTokens": statistics.mean(r["contextTokens"] for r in values)}
    (args.run_dir / "scores.json").write_text(json.dumps({"sharedRanksVerified": True, "sharedQueryEmbeddingVerified": True,
        "httpRequests": len(completed), "views": len(views), "modes": report}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
