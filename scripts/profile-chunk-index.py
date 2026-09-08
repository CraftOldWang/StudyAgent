"""Profile actual indexed chunks using the same jtokkit binary and local token unit as the app."""
import argparse
import base64
import hashlib
import json
import math
import statistics
import subprocess
import zipfile
from collections import defaultdict
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--ingest-state", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--java", default=r"D:\Softwares\1Portable_softwares\Scoop\apps\openjdk21\current\bin\java.exe")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    state = json.loads(args.ingest_state.read_text(encoding="utf-8"))
    ids = [str(d["documentId"]) for d in state["documents"].values()]
    response = requests.post(f"http://localhost:9200/{config['physicalIndex']}/_search", timeout=30,
        json={"size": 10000, "query": {"terms": {"document_id": ids}}, "_source": {"excludes": ["embedding"]}})
    response.raise_for_status()
    values = response.json()["hits"]
    assert len(values["hits"]) == values["total"]["value"] < 10000
    chunks = [r["_source"] for r in values["hits"]]
    assert {c["document_id"] for c in chunks} == set(ids)
    jar_path = Path(".eval/jtokkit-1.1.0.jar")
    with zipfile.ZipFile("target/study-agent-0.0.1-SNAPSHOT.jar") as archive:
        jar_path.write_bytes(archive.read("BOOT-INF/lib/jtokkit-1.1.0.jar"))
    text_path = args.run_dir / "chunk-text.tsv"
    text_path.write_text("".join(c["chunk_id"] + "\t" + base64.b64encode(c["content"].encode()).decode() + "\n" for c in chunks), encoding="utf-8")
    output = subprocess.check_output([args.java, "-Xmx128m", "-cp", str(jar_path), "scripts/ChunkTokenLengths.java", str(text_path)])
    (args.run_dir / "chunk-token-counts.tsv").write_bytes(output)
    lengths = {line.split("\t")[0]: int(line.split("\t")[1]) for line in output.decode().splitlines()}
    parent = {c["chunk_id"]: c for c in chunks if c["chunk_type"] == "PARENT"}
    children = [c for c in chunks if c["chunk_type"] == "CHILD"]
    equal_parents = {c["parent_chunk_id"] for c in children if c["content_hash"] == parent[c["parent_chunk_id"]]["content_hash"]}
    distributions = {}
    for kind in ["CHILD", "PARENT"]:
        values = sorted(lengths[c["chunk_id"]] for c in chunks if c["chunk_type"] == kind)
        limit = config["childTokens"] if kind == "CHILD" else config["parentTokens"]
        assert max(values) <= limit
        distributions[kind] = {"count": len(values), "min": min(values), "median": statistics.median(values),
                               "p95NearestRank": values[math.ceil(len(values) * .95) - 1], "max": max(values), "total": sum(values)}
    ranges = defaultdict(list)
    for c in children:
        loc = json.loads(c["source_location"])
        ranges[c["document_id"]].append((loc["startOffset"], loc["endOffset"]))
    total = sum(b - a for spans in ranges.values() for a, b in spans)
    unique = 0
    for spans in ranges.values():
        last = -1
        for a, b in sorted(spans):
            unique += max(0, b - max(a, last))
            last = max(last, b)
    stats = requests.get(f"http://localhost:9200/{config['physicalIndex']}/_stats/store,docs", timeout=15)
    stats.raise_for_status()
    primary = stats.json()["_all"]["primaries"]
    assert primary["docs"]["count"] == len(chunks), "Physical index contains extra documents"
    report = {"config": config["id"], "tokenizer": "jtokkit 1.1.0 CL100K_BASE countTokensOrdinary",
              "tokenizerJarSha256": hashlib.sha256(jar_path.read_bytes()).hexdigest(), "lengths": distributions,
              "equalParentChildParents": len(equal_parents), "equalParentChildParentRatio": len(equal_parents) / len(parent),
              "identicalChildDuplicates": len(children) - len({c["content_hash"] for c in children}),
              "overlappingSourceUtf16Units": total - unique, "sourceOverlapRatio": (total - unique) / total,
              "primaryIndexStoreBytes": primary["store"]["size_in_bytes"],
              "storeNote": "Actual primary-store snapshot; Lucene segment layout is not controlled or force-merged",
              "minimumRawVectorBytes": len(children) * config["dimensions"] * 4}
    (args.run_dir / "index-profile.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
