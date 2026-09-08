"""Deploy one versioned eval configuration, ingest dev courses, and call the production scorer."""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--deploy-only", action="store_true")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    env = {"STUDY_AGENT_RAG_CHUNK_STRATEGY": config["strategy"],
           "STUDY_AGENT_RAG_CHUNK_SIZE": str(config["childTokens"]),
           "STUDY_AGENT_RAG_CHUNK_OVERLAP": str(config["childOverlap"]),
           "STUDY_AGENT_RAG_PARENT_CHUNK_SIZE": str(config["parentTokens"]),
           "STUDY_AGENT_RAG_PARENT_CHUNK_OVERLAP": str(config["parentOverlap"]),
           "STUDY_AGENT_RAG_BM25_CANDIDATE_SIZE": str(config["bm25CandidateSize"]),
           "STUDY_AGENT_RAG_VECTOR_CANDIDATE_SIZE": str(config["vectorCandidateSize"]),
           "STUDY_AGENT_RAG_RRF_K": str(config["rrfK"]),
           "STUDY_AGENT_RAG_CONTEXT_MAX_TOKENS": str(config["contextMaxTokens"]),
           "STUDY_AGENT_ELASTICSEARCH_PHYSICAL_INDEX": config["physicalIndex"],
           "STUDY_AGENT_ELASTICSEARCH_READ_ALIAS": config["readAlias"],
           "STUDY_AGENT_ELASTICSEARCH_WRITE_ALIAS": config["writeAlias"]}
    root = Path(".eval/runs")
    corpus_dir = root / ("corpus-" + config["id"])
    corpus_dir.mkdir(parents=True, exist_ok=True)
    override = Path(".eval/chunk-experiment.compose.json")
    payload = json.dumps({"services": {"eval-app": {"environment": env}}}, indent=2) + "\n"
    override.write_text(payload, encoding="utf-8")
    (corpus_dir / "deployment.compose.json").write_text(payload, encoding="utf-8")
    subprocess.run(["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.eval.yml", "-f", str(override),
                    "up", "-d", "--no-deps", "eval-app"], check=True)
    inspected = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    actual_env = dict(item.split("=", 1) for item in inspected["Config"]["Env"])
    assert all(actual_env.get(key) == value for key, value in env.items()), "Container configuration differs"
    deadline = time.monotonic() + 60
    while True:
        try:
            response = requests.get("http://localhost:8080/api/knowledge-bases", headers={"X-User-Id": "1"}, timeout=2)
            if response.status_code == 200 and response.json().get("code") == 0:
                break
        except requests.RequestException:
            pass
        if time.monotonic() > deadline:
            raise RuntimeError("Eval app did not become ready; inspect its logs before resuming")
        time.sleep(1)
    alias = requests.get(f"http://localhost:9200/_alias/{config['readAlias']}", timeout=10)
    alias.raise_for_status()
    assert set(alias.json()) == {config["physicalIndex"]}, "Read alias points at an unexpected index"
    if args.deploy_only:
        return
    subprocess.run([sys.executable, "scripts/ingest-eval-corpus.py", "--run-dir", str(corpus_dir), "--courses", "algorithms", "os"], check=True)
    subprocess.run([sys.executable, "scripts/run-rag-evaluation.py", "--ingest-state", str(corpus_dir / "ingest-state.json"),
                    "--config", str(args.config), "--run-dir", str(root / ("dev-" + config["id"])), "--split", "dev", "--resume"], check=True)


if __name__ == "__main__":
    main()
