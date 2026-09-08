"""Score frozen source spans using the real production search endpoint; preserve all attempts."""
import argparse
import hashlib
import json
import re
import statistics
import subprocess
import sys
import time
import unicodedata
from collections import Counter
from pathlib import Path

import requests


def normalize(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text)).casefold()


def matches(hit, question, document_hashes):
    doc = str(hit["provenance"]["documentId"])
    if doc not in document_hashes:
        raise ValueError("Retrieved a document outside the frozen corpus")
    body = normalize(hit["content"])
    return any(e["sourceSha256"] == document_hashes[doc] and normalize(e["quote"])
               and normalize(e["quote"]) in body for e in question["evidence"])


def score(data, question, document_hashes):
    ranks = [i for i, hit in enumerate(data["rankedChildren"], 1) if matches(hit, question, document_hashes)]
    return {"hitAt5": int(any(i <= 5 for i in ranks)), "reciprocalRankAt10": 1 / ranks[0] if ranks and ranks[0] <= 10 else 0,
            "contextEvidenceHit": int(any(matches(hit, question, document_hashes) for hit in data["hits"])),
            "contextTokens": data["contextTokens"], "firstAnnotatedEvidenceRank": ranks[0] if ranks else None}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--ingest-state", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--split", choices=["dev", "validation"], required=True)
    parser.add_argument("--modes", nargs="+", choices=["BM25", "VECTOR", "RRF", "PARENT"], default=["RRF"])
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    state = json.loads(args.ingest_state.read_text(encoding="utf-8"))
    gold = [json.loads(line) for line in Path("eval/rag/gold-v1.jsonl").read_text(encoding="utf-8").splitlines()]
    questions = [q for q in gold if q["split"] == args.split]
    courses = sorted({q["course"] for q in questions})
    selected_docs = {str(doc["documentId"]): doc for doc in state["documents"].values()
                     if str(doc["knowledgeBaseId"]) in {str(state["knowledgeBases"][c]) for c in courses}}
    doc_hashes = {did: doc["sourceSha256"] for did, doc in selected_docs.items()}
    if any(doc["lastStatus"]["pipelineStatus"] != "INDEXED" for doc in selected_docs.values()):
        raise ValueError("Corpus contains unfinished documents")
    es = requests.post(f"http://localhost:9200/{config['physicalIndex']}/_search", timeout=30,
                       json={"size": 10000, "query": {"bool": {"filter": [{"term": {"user_id": "1"}},
                             {"terms": {"document_id": list(selected_docs)}}]}}, "_source": {"excludes": ["embedding"]}})
    es.raise_for_status()
    indexed = es.json()["hits"]["hits"]
    assert len(indexed) == es.json()["hits"]["total"]["value"] < 10000
    chunks = [item["_source"] for item in indexed]
    count_response = requests.get(f"http://localhost:9200/{config['physicalIndex']}/_count", timeout=10)
    count_response.raise_for_status()
    assert count_response.json()["count"] == len(chunks), "Index includes documents outside this evaluation split; BM25 statistics would be confounded"
    settings_response = requests.get(f"http://localhost:9200/{config['physicalIndex']}/_settings", timeout=10)
    settings_response.raise_for_status()
    index_settings = settings_response.json()[config["physicalIndex"]]["settings"]["index"]
    assert index_settings["number_of_shards"] == "1", "This small-corpus protocol requires one primary shard"
    assert {c["document_id"] for c in chunks} == set(selected_docs)
    assert {c["chunker_version"] for c in chunks} == {config["chunkerVersion"]}
    assert {c["embedding_model"] for c in chunks} == {config["embeddingModel"]}
    # A reused embedding inherits its original measured cost; cache hits never become free in a configuration comparison.
    starts, costs = {}, {}
    for line in Path(".eval/embedding-calls.jsonl").read_text(encoding="utf-8").splitlines():
        e = json.loads(line)
        if e["status"] == "STARTED":
            starts[e["callId"]] = e
        elif e["status"] == "SUCCEEDED" and e.get("usageAvailable"):
            s = starts[e["callId"]]
            if s["purpose"] == "DOCUMENT" and s["model"] == config["embeddingModel"] and s["dimensions"] == 1024:
                costs[s["contentSha256"]] = e["totalTokens"]
    children = [c for c in chunks if c["chunk_type"] == "CHILD"]
    missing = [c["content_hash"] for c in children if c["content_hash"] not in costs]
    assert not missing, "Cannot report comparable token cost without provider usage for every child"
    corpus_stats = {"documents": len(selected_docs), "chunks": dict(Counter(c["chunk_type"] for c in chunks)),
                    "logicalDocumentTokens": sum(costs[c["content_hash"]] for c in children),
                    "uniqueContentDocumentTokens": sum(costs[h] for h in {c["content_hash"] for c in children}),
                    "tokenNote": "Logical per-child provider tokens, including cached artifacts; not incremental billed usage"}
    manifest = {"goldSha256": digest("eval/rag/gold-v1.jsonl"), "ingestStateSha256": digest(args.ingest_state),
                "config": config, "split": args.split, "modes": args.modes, "topK": 10,
                "jarSha256": digest("target/study-agent-0.0.1-SNAPSHOT.jar"),
                "gitSha": subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip(),
                "runnerSha256": digest(__file__), "corpusStats": corpus_stats,
                "indexPopulationChecked": True, "primaryShards": int(index_settings["number_of_shards"]),
                "metric": "Full annotated span within one retrieved chunk after NFKC/whitespace/case normalization"}
    manifest_path = args.run_dir / "manifest.json"
    raw_path = args.run_dir / "responses.jsonl"
    if manifest_path.exists():
        if not args.resume or json.loads(manifest_path.read_text(encoding="utf-8")) != manifest:
            raise RuntimeError("Existing run or changed inputs; inspect before resuming")
    else:
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    previous = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()] if raw_path.exists() else []
    completed = {(r["questionId"], r["mode"]): r for r in previous if r.get("status") == 200 and r.get("response", {}).get("code") == 0}
    http = requests.Session()
    http.headers["X-User-Id"] = "1"
    for question in questions:
        for mode in args.modes:
            key = question["id"], mode
            if key in completed:
                continue
            kb = str(state["knowledgeBases"][question["course"]])
            row = {"questionId": question["id"], "course": question["course"], "mode": mode,
                   "query": question["question"], "knowledgeBaseId": kb, "startedAt": time.time()}
            start = time.perf_counter()
            try:
                response = http.post(f"http://localhost:8080/api/knowledge-bases/{kb}/search",
                                     json={"query": question["question"], "mode": mode, "topK": 10}, timeout=120)
                row.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
                response.raise_for_status()
                assert row["response"]["code"] == 0 and row["traceId"], "Missing successful traced response"
                data = row["response"]["data"]
                assert data["mode"] == mode and data["contextTokens"] <= config["contextMaxTokens"]
                assert len({h["chunkId"] for h in data["hits"]}) == len(data["hits"])
                if question["answerable"]:
                    row["score"] = score(data, question, doc_hashes)
            finally:
                row["elapsedMillis"] = (time.perf_counter() - start) * 1000
                with raw_path.open("a", encoding="utf-8") as stream:
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
            completed[key] = row
            print(json.dumps({"id": key[0], "mode": mode, "status": row["status"]}), flush=True)
    summaries = {}
    for mode in args.modes:
        by_course = {}
        for course in courses:
            rows = [completed[q["id"], mode] for q in questions if q["course"] == course and q["answerable"]]
            by_course[course] = {"n": len(rows), "hitAt5": statistics.mean(r["score"]["hitAt5"] for r in rows),
                                 "mrrAt10": statistics.mean(r["score"]["reciprocalRankAt10"] for r in rows),
                                 "contextEvidenceHit": statistics.mean(r["score"]["contextEvidenceHit"] for r in rows),
                                 "meanContextTokens": statistics.mean(r["score"]["contextTokens"] for r in rows),
                                 "medianSearchMillis": statistics.median(r["elapsedMillis"] for r in rows)}
        summaries[mode] = {"byCourse": by_course, "worstCourseHitAt5": min(c["hitAt5"] for c in by_course.values()),
                           "macroHitAt5": statistics.mean(c["hitAt5"] for c in by_course.values()),
                           "macroMrrAt10": statistics.mean(c["mrrAt10"] for c in by_course.values())}
    if "RRF" in args.modes and "PARENT" in args.modes:
        for q in questions:
            ranks = [[h["chunkId"] for h in completed[q["id"], mode]["response"]["data"]["rankedChildren"]] for mode in ("RRF", "PARENT")]
            assert ranks[0] == ranks[1], "Parent expansion changed the child ranking"
    report = {"split": args.split, "modes": summaries, "corpusStats": corpus_stats,
              "answerable": sum(q["answerable"] for q in questions), "insufficient": sum(not q["answerable"] for q in questions),
              "insufficientNote": "Retrieval alone cannot establish refusal correctness; answer evaluation is separate"}
    (args.run_dir / "scores.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
