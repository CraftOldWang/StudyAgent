"""Real local ASR -> configured embedding -> index -> source retrieval, with an app process kill.

The audio stays on the local ASR worker. The resulting transcript is sent to the
application's configured embedding provider. No chat/DeepSeek request is made.
Raw transcripts and API responses remain in the ignored local run directory.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

import requests


def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=root", "study-agent-mysql", "mysql",
        "--default-character-set=utf8mb4", "--raw", "-uroot", "-D", "study_agent_eval", "-N"],
        input=statement.encode(), capture_output=True, check=True)
    return result.stdout.decode("utf-8").strip()


def rows(path):
    if not path.exists(): return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--resume", action="store_true", help="Observe the original interrupted document without uploading or killing again")
    args = parser.parse_args()
    root = args.run_dir; root.mkdir(parents=True, exist_ok=True)
    report_path = root / "report.json"
    assert args.resume == report_path.exists(), "Use --resume for an existing run, or a new directory for a new run"
    with args.file.open("rb") as f: digest = hashlib.file_digest(f, "sha256").hexdigest()
    health = requests.get("http://127.0.0.1:8767/health", timeout=10).json()
    assert health["ready"] and health["config"]["runtimeVersion"] == "4.4.0", health
    settings = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(item.split("=", 1) for item in settings["Config"]["Env"])
    assert env.get("STUDY_AGENT_ELASTICSEARCH_PHYSICAL_INDEX") == "eval-m8-asr", "Isolated ASR index required"
    if not args.resume:
        assert not any(r.get("inputSha256") == digest and r["status"] == "STARTED" for r in rows(Path(".eval/asr-cache/attempts.jsonl"))), "Use an untranscribed representative media sample"
    llm_before = sum(r["status"] == "STARTED" for r in rows(Path(".eval/model-calls.jsonl")))
    report = {"status": "RUNNING", "startedAt": datetime.now(timezone.utc).isoformat(), "events": [],
              "source": str(args.file.resolve()), "sha256": digest, "sourceBytes": args.file.stat().st_size,
              "worker": health, "llmAttemptsBefore": llm_before,
              "jarSha256": hashlib.file_digest(Path("target/study-agent-0.0.1-SNAPSHOT.jar").open("rb"), "sha256").hexdigest()}

    if args.resume:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        assert report["sha256"] == digest
        llm_before = report["llmAttemptsBefore"]
        report["observationResumedAt"] = datetime.now(timezone.utc).isoformat()

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def api(method, path, **kwargs):
        response = requests.request(method, "http://127.0.0.1:8080" + path,
                                    headers={"X-User-Id": "1"}, timeout=90, **kwargs)
        response.raise_for_status(); envelope = response.json()
        assert envelope["code"] == 0, envelope
        return envelope["data"]

    if not args.resume:
        kb = api("POST", "/api/knowledge-bases", json={"name": "编译原理录音 · ASR恢复验收"})["id"]
        report["knowledgeBaseId"] = kb; save()
        with args.file.open("rb") as source:
            uploaded = api("POST", "/api/files/upload", data={"knowledgeBaseId": kb},
                           files={"file": (args.file.name, source, "audio/mp4")})
        doc = uploaded["documentId"]; report["documentId"] = doc; save()
        ledger = Path(".eval/asr-cache/attempts.jsonl")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            matching = [r for r in rows(ledger) if r.get("inputSha256") == digest and r["status"] == "STARTED"]
            if matching:
                job = matching[0]["jobKey"]; break
            time.sleep(.5)
        else: raise RuntimeError("ASR never started; inspect document failure")
        report["jobKey"] = job
        before_lease = sql(f"SELECT lease_until FROM documents WHERE id={doc};")
        time.sleep(15)
        after_lease = sql(f"SELECT lease_until FROM documents WHERE id={doc};")
        assert after_lease > before_lease, (before_lease, after_lease)
        assert not any(r.get("jobKey") == job and r["status"] == "SUCCEEDED" for r in rows(ledger)), "ASR completed before interruption"
        report["leaseBefore"] = before_lease; report["leaseAfterHeartbeat"] = after_lease
        subprocess.run(["docker", "kill", "study-agent-eval-app-1"], check=True, capture_output=True)
        report["killedAt"] = datetime.now(timezone.utc).isoformat(); save()
        subprocess.run(["docker", "start", "study-agent-eval-app-1"], check=True, capture_output=True)
        report["restartedAt"] = datetime.now(timezone.utc).isoformat(); save()
        print(json.dumps({"documentId": doc, "asrJob": job, "appKilledAndRestarted": True}), flush=True)
    else:
        kb, doc, job = (report[k] for k in ["knowledgeBaseId", "documentId", "jobKey"])
        ledger = Path(".eval/asr-cache/attempts.jsonl")
        save()
    deadline = time.monotonic() + 900
    previous = None
    while time.monotonic() < deadline:
        try:
            documents = api("GET", f"/api/knowledge-bases/{kb}/documents")
        except requests.ConnectionError:
            time.sleep(2); continue
        except requests.HTTPError as error:
            if error.response.status_code not in (502, 503, 504): raise
            time.sleep(2); continue
        current = next(d for d in documents if d["id"] == doc)
        if current["pipelineStatus"] != previous:
            previous = current["pipelineStatus"]
            report["events"].append({"at": datetime.now(timezone.utc).isoformat(), "document": current}); save()
            print("stage", previous, flush=True)
        if previous == "FAILED": raise RuntimeError(current["errorMessage"])
        if previous == "INDEXED": break
        time.sleep(2)
    else: raise RuntimeError("Recovery deadline exceeded; original document remains recoverable")
    attempts = [r for r in rows(ledger) if r.get("jobKey") == job]
    assert sum(r["status"] == "STARTED" for r in attempts) == 1, attempts
    assert sum(r["status"] == "SUCCEEDED" for r in attempts) == 1, attempts
    assert any(r["status"] == "CACHE_HIT" for r in attempts), attempts
    report["asrAttempts"] = attempts
    report["database"] = sql(f"SELECT pipeline_status,attempt_count,parser_version,asr_result_key,parsed_text_key,asr_metadata_json FROM documents WHERE id={doc};")
    report["chunkCount"] = int(sql(f"SELECT COUNT(*) FROM document_chunks WHERE document_id={doc};"))
    for mode in ["BM25", "RRF"]:
        found = api("POST", f"/api/knowledge-bases/{kb}/search", json={"query": "什么是直接支配者？", "mode": mode, "topK": 5})
        (root / (mode.lower() + "-search.json")).write_text(json.dumps(found, ensure_ascii=False, indent=2), encoding="utf-8")
        report[mode + "Search"] = found
    report["embeddingEvents"] = [r for r in rows(Path(".eval/embedding-calls.jsonl")) if r.get("operation") == f"INGEST /documents/{doc}"]
    after = sum(r["status"] == "STARTED" for r in rows(Path(".eval/model-calls.jsonl")))
    assert after == llm_before
    report["llmAttemptsAfter"] = after
    report["status"] = "INGEST_AND_RETRIEVAL_FINISHED"
    report["completedAt"] = datetime.now(timezone.utc).isoformat()
    report["limitations"] = ["Search content/source assertions are reviewed from the saved production API responses.",
                              "No learning/chat call; this does not complete the separate learning acceptance."]
    save()
    print(json.dumps({"documentId": doc, "status": report["status"], "asrInferenceAttempts": 1,
                      "chunkCount": report["chunkCount"], "llmCallsAdded": after-llm_before}), flush=True)


if __name__ == "__main__":
    main()
