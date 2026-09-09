"""Resume interleaved, frozen, real-API sessions; never hide failed attempts.

Each course supplies nine prepared replicas and one immutable user script.
The default limit runs a first pair for inspection before expanding to all 18.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--algorithms", type=Path, required=True)
    parser.add_argument("--os", type=Path, required=True)
    parser.add_argument("--scripts", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--max-sessions", type=int, default=2)
    parser.add_argument("--prepare-only", action="store_true", help="Freeze schedule and hashes without starting model calls")
    parser.add_argument("--retry-failed", action="store_true")
    args = parser.parse_args()
    assert 1 <= args.max_sessions <= 18
    roots = {"algorithms": args.algorithms, "os": args.os}
    inputs = {course: json.loads((root / "replicas.json").read_text(encoding="utf-8")) for course, root in roots.items()}
    for course, data in inputs.items():
        assert data["blueprint"]["course"] == course and not data["blueprint"]["pilot"]
        assert len(data["replicas"]) == 9 and all(r["prepared"] for r in data["replicas"])
        assert {(r["strategy"], r["repeat"]) for r in data["replicas"]} == {
            (strategy, repeat) for strategy in ["THRESHOLD", "WHOLE_HISTORY", "LOCAL"] for repeat in [1, 2, 3]}
    frozen_paths = [args.config, Path("target/study-agent-0.0.1-SNAPSHOT.jar"),
                    Path("scripts/run-learning-conversation-smoke.py"), Path(__file__),
                    Path("scripts/report-learning-usage.py"), Path("scripts/collect-learning-evidence.py"),
                    Path("scripts/report-compaction-experiment.py"),
                    Path("eval/planning/compaction-quality-v1.json"),
                    *[args.scripts / (course + ".json") for course in roots],
                    *[root / "replicas.json" for root in roots.values()]]
    fingerprints = {p.as_posix(): digest(p) for p in frozen_paths}
    config = json.loads(args.config.read_text(encoding="utf-8"))
    inspected = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(item.split("=", 1) for item in inspected["Config"]["Env"])
    assert inspected["State"]["Running"]
    assert env.get("STUDY_AGENT_ELASTICSEARCH_PHYSICAL_INDEX") == config["physicalIndex"]
    # Only non-secret runtime settings become experiment evidence.
    setting_names = ["STUDY_AGENT_RAG_CHUNK_STRATEGY", "STUDY_AGENT_RAG_CHUNK_SIZE", "STUDY_AGENT_RAG_CHUNK_OVERLAP",
                     "STUDY_AGENT_RAG_PARENT_CHUNK_SIZE", "STUDY_AGENT_RAG_PARENT_CHUNK_OVERLAP",
                     "STUDY_AGENT_RAG_BM25_CANDIDATE_SIZE", "STUDY_AGENT_RAG_VECTOR_CANDIDATE_SIZE",
                     "STUDY_AGENT_RAG_RRF_K", "STUDY_AGENT_RAG_CONTEXT_MAX_TOKENS",
                     "STUDY_AGENT_LEARNING_CONVERSATION_THRESHOLD_TOKENS", "STUDY_AGENT_LEARNING_CONVERSATION_SUMMARY_TOKENS",
                     "STUDY_AGENT_LEARNING_CONVERSATION_REPLY_TOKENS", "STUDY_AGENT_LEARNING_CONVERSATION_MAX_ITERATIONS"]
    observed = {k: env[k] for k in setting_names if k in env}
    root = args.run_dir; root.mkdir(parents=True, exist_ok=True)
    path = root / "experiment.json"
    if path.exists():
        state = json.loads(path.read_text(encoding="utf-8"))
        assert state["fingerprints"] == fingerprints and state["runtimeSettings"] == observed, "Frozen experiment inputs changed"
    else:
        schedule = []
        for i in range(9):
            for course in (["algorithms", "os"] if i % 2 == 0 else ["os", "algorithms"]):
                replica = inputs[course]["replicas"][i]
                schedule.append({**replica, "course": course, "knowledgeBaseId": str(inputs[course]["blueprint"]["knowledgeBaseId"]),
                                 "status": "PENDING"})
        state = {"version": "compaction-experiment-v1", "fingerprints": fingerprints, "runtimeSettings": observed,
                 "gitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                 "createdAt": datetime.now(timezone.utc).isoformat(), "schedule": schedule,
                 "scope": "18 sessions, two frozen five-point plans, three strategies, three repetitions; real API calls only"}

    def save():
        path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    save()
    if args.prepare_only:
        print("Frozen 18-session schedule; no learning calls started")
        return
    for item in state["schedule"][:args.max_sessions]:
        if item["status"] == "PASSED":
            continue
        if item["status"] == "FAILED" and not args.retry_failed:
            raise RuntimeError("Inspect the original failed session before using --retry-failed")
        sid = item["sessionId"]
        run = root / sid
        run.mkdir(exist_ok=True)
        item.update(status="RUNNING", lastStartedAt=datetime.now(timezone.utc).isoformat()); save()
        command = [sys.executable, "scripts/run-learning-conversation-smoke.py", "--session-id", sid,
                   "--knowledge-base-id", item["knowledgeBaseId"], "--run-dir", str(run), "--max-points", "5",
                   "--disconnect-first-text", "--script", str(args.scripts / (item["course"] + ".json"))]
        if args.retry_failed:
            command.append("--retry-failed")
        print(json.dumps({k: item[k] for k in ["course", "repeat", "strategy", "sessionId", "status"]}), flush=True)
        with (run / "runner.log").open("a", encoding="utf-8") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        item.update(status="COLLECTING" if result.returncode == 0 else "FAILED", lastExitCode=result.returncode,
                    lastFinishedAt=datetime.now(timezone.utc).isoformat()); save()
        subprocess.run([sys.executable, "scripts/report-learning-usage.py", "--session-id", sid,
                        "--output", str(run / "usage.json")], check=True)
        print(item["status"], sid, flush=True)
        if result.returncode:
            raise RuntimeError("Session failed; preserved request IDs, raw results, log and usage. No later session started.")
        subprocess.run([sys.executable, "scripts/collect-learning-evidence.py", "--session-id", sid,
                        "--output-dir", str(run / "persisted-evidence")], check=True)
        item["status"] = "PASSED"; save()


if __name__ == "__main__":
    main()
