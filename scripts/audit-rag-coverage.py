"""Measure whether complete annotated spans can fit in any indexed child/parent, independently of rank."""
import argparse
import importlib.util
import json
from pathlib import Path
import requests


spec = importlib.util.spec_from_file_location("scorer", Path(__file__).with_name("run-rag-evaluation.py"))
scorer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scorer)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--ingest-state", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--split", choices=["dev", "validation"], required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    state = json.loads(args.ingest_state.read_text(encoding="utf-8"))
    hashes = {str(d["documentId"]): d["sourceSha256"] for d in state["documents"].values()}
    response = requests.post(f"http://localhost:9200/{config['physicalIndex']}/_search", timeout=30,
        json={"size": 10000, "query": {"bool": {"filter": [{"term": {"user_id": "1"}}, {"terms": {"document_id": list(hashes)}}]}},
              "_source": ["document_id", "chunk_id", "chunk_type", "content"]})
    response.raise_for_status()
    values = response.json()["hits"]
    assert len(values["hits"]) == values["total"]["value"] < 10000
    chunks = [r["_source"] for r in values["hits"]]
    questions = [q for q in map(json.loads, Path("eval/rag/gold-v1.jsonl").read_text(encoding="utf-8").splitlines()) if q["split"] == args.split and q["answerable"]]
    rows = []
    for q in questions:
        match = {kind: [c["chunk_id"] for c in chunks if c["chunk_type"] == kind and scorer.matches(
                        {"content": c["content"], "provenance": {"documentId": c["document_id"]}}, q, hashes)] for kind in ["CHILD", "PARENT"]}
        rows.append({"id": q["id"], "course": q["course"], "quoteCharacters": len(q["evidence"][0]["quote"]),
                     "childPossible": bool(match["CHILD"]), "parentPossible": bool(match["PARENT"]), "matchedChunks": match})
    report = {"split": args.split, "config": config["id"], "questions": len(rows),
              "childPossible": sum(r["childPossible"] for r in rows), "parentPossible": sum(r["parentPossible"] for r in rows),
              "note": "All-index oracle for full annotated span presence, not retrieval or semantic answer quality", "rows": rows}
    (args.run_dir / "coverage-audit.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: val for key, val in report.items() if key != "rows"}))


if __name__ == "__main__":
    main()
