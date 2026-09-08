"""Batch probes through the production search API. Results are raw evidence, not quality scores."""
import argparse
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, required=True, help="JSONL: id, knowledgeBaseId, query")
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--context-budget", type=int, default=4096)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    raw_path = args.run_dir / "responses.jsonl"
    if raw_path.exists() and not args.resume:
        raise RuntimeError("Existing results; inspect them or explicitly resume")
    cases = [json.loads(line) for line in args.cases.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    case_hash = hashlib.sha256(args.cases.read_bytes()).hexdigest()
    manifest_path = args.run_dir / "manifest.json"
    manifest = {"casesSha256": case_hash, "gitSha": subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip(),
                "topK": args.top_k, "expectedContextTextBudget": args.context_budget,
                "label": "API_PROBE_ONLY", "modes": ["BM25", "VECTOR", "RRF", "PARENT"]}
    jar = Path("target/study-agent-0.0.1-SNAPSHOT.jar")
    with jar.open("rb") as file:
        manifest["jarSha256"] = hashlib.file_digest(file, "sha256").hexdigest()
    manifest["trackedChangesPresent"] = bool(subprocess.check_output(["git", "diff", "HEAD", "--name-only"]))
    if manifest_path.exists():
        existing = json.loads(manifest_path.read_text(encoding="utf-8"))
        if existing != manifest:
            raise RuntimeError("Resume configuration differs from the recorded run")
    else:
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()] if raw_path.exists() else []
    completed = {(row["caseId"], row["mode"]): row for row in records
                 if row.get("status") == 200 and row.get("response", {}).get("code") == 0}
    session = requests.Session()
    session.headers["X-User-Id"] = "1"
    for case in cases:
        for mode in manifest["modes"]:
            if (case["id"], mode) in completed:
                continue
            row = {"caseId": case["id"], "knowledgeBaseId": case["knowledgeBaseId"], "mode": mode,
                   "query": case["query"], "startedAt": time.time()}
            started = time.perf_counter()
            try:
                response = session.post(f"http://localhost:8080/api/knowledge-bases/{case['knowledgeBaseId']}/search",
                                        json={"query": case["query"], "mode": mode, "topK": args.top_k}, timeout=120)
                row.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
                if not row["traceId"]:
                    raise RuntimeError("Production response is missing the required trace ID")
                response.raise_for_status()
                if row["response"]["code"] != 0:
                    raise RuntimeError("Application failure; inspect the raw result")
            except requests.RequestException as error:
                row["transportError"] = type(error).__name__
                raise
            finally:
                row["elapsedMillis"] = (time.perf_counter() - started) * 1000
                with raw_path.open("a", encoding="utf-8") as file:
                    file.write(json.dumps(row, ensure_ascii=False) + "\n")
            completed[(case["id"], mode)] = row
    ledger = [json.loads(line) for line in Path(".eval/embedding-calls.jsonl").read_text(encoding="utf-8").splitlines()]
    checks = []
    for case in cases:
        rows = {mode: completed[(case["id"], mode)] for mode in manifest["modes"]}
        values = {mode: row["response"]["data"] for mode, row in rows.items()}
        ranks = {mode: [hit["chunkId"] for hit in data["rankedChildren"]] for mode, data in values.items()}
        bm25_calls = [e for e in ledger if e.get("traceId") == rows["BM25"]["traceId"] and e["status"] == "STARTED"]
        assert not bm25_calls, "BM25 unexpectedly invoked embedding"
        assert ranks["RRF"] == ranks["PARENT"], "RRF and parent mode did not retain identical child ranks"
        for data in values.values():
            ids = [hit["chunkId"] for hit in data["hits"]]
            assert len(ids) == len(set(ids)), "Duplicate context IDs"
            assert data["contextTokens"] <= args.context_budget, "Context text exceeds the budget"
            known = {hit["chunkId"] for hit in data["rankedChildren"]}
            assert all(child["chunkId"] in known for match in data["contextMatches"] for child in match["matchedChildren"])
        checks.append({"caseId": case["id"], "sameRrfParentChildRanks": True, "bm25EmbeddingCalls": 0,
                       "modes": {mode: {"rankedChildren": len(data["rankedChildren"]), "contexts": len(data["hits"]),
                                         "contextTokens": data["contextTokens"], "traceId": rows[mode]["traceId"]}
                                 for mode, data in values.items()}})
    report = {"status": "PASSED", "qualityBenchmark": False, "checks": checks}
    (args.run_dir / "checks.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
