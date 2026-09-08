"""Kill only the named eval app after an embedding starts, then observe lease recovery via API."""
import argparse
import hashlib
import json
import mimetypes
import subprocess
import time
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--resume", action="store_true", help="Observe an existing interrupted run without any new mutation")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.run_dir / "report.json"
    if report_path.exists() and not args.resume:
        raise RuntimeError("Existing run; inspect and continue its document instead of uploading again")
    report = json.loads(report_path.read_text(encoding="utf-8")) if args.resume else {"container": "study-agent-eval-app-1", "events": []}

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def api(method, path, **kwargs):
        response = requests.request(method, "http://localhost:8080" + path,
                                    headers={"X-User-Id": "1"}, timeout=180, **kwargs)
        report["events"].append({"method": method, "path": path, "status": response.status_code,
                                 "traceId": response.headers.get("X-Trace-Id"), "at": time.time()})
        save()
        response.raise_for_status()
        body = response.json()
        if body["code"] != 0:
            raise RuntimeError(body)
        return body["data"]

    def ledger_calls(doc):
        rows = [json.loads(line) for line in Path(".eval/embedding-calls.jsonl").read_text(encoding="utf-8").splitlines()]
        ids = {row["callId"] for row in rows if row.get("operation") == f"INGEST /documents/{doc}"}
        return [row for row in rows if row["callId"] in ids]

    if not args.resume:
        kb = api("POST", "/api/knowledge-bases", json={"name": "Lease recovery " + args.run_dir.name})["id"]
        report["knowledgeBaseId"] = kb
        with args.file.open("rb") as source:
            report["fileSha256"] = hashlib.file_digest(source, "sha256").hexdigest()
            source.seek(0)
            result = api("POST", "/api/files/upload", data={"knowledgeBaseId": kb},
                         files={"file": (args.file.name, source, mimetypes.guess_type(args.file.name)[0])})
        doc = result["documentId"]
        report["documentId"] = doc
        save()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            rows = ledger_calls(doc)
            if any(row["status"] == "SUCCEEDED" for row in rows):
                break
            time.sleep(0.025)
        else:
            raise RuntimeError("No completed provider response observed; app was not terminated")
        # Keep the interrupted attempt and its last acknowledged usage; never mark missing terminal usage as zero.
        report["callsBeforeKill"] = rows
        subprocess.run(["docker", "kill", report["container"]], check=True, capture_output=True, text=True)
        report["killedAt"] = time.time()
        save()
        subprocess.run(["docker", "start", report["container"]], check=True, capture_output=True, text=True)
        report["restartedAt"] = time.time()
        save()
        print(json.dumps({"documentId": doc, "killed": True, "restarted": True}), flush=True)
    else:
        kb, doc = report["knowledgeBaseId"], report["documentId"]
        report["observationResumedAt"] = time.time()
        save()
    deadline = time.monotonic() + 450
    while time.monotonic() < deadline:
        try:
            rows = api("GET", f"/api/knowledge-bases/{kb}/documents")
        except requests.ConnectionError:
            time.sleep(2)
            continue
        except requests.HTTPError as error:
            if error.response.status_code not in (502, 503, 504):
                raise
            time.sleep(2)
            continue
        current = next(row for row in rows if str(row["id"]) == str(doc))
        report["lastObservedDocument"] = current
        save()
        if current["pipelineStatus"] == "INDEXED":
            report["callsAfterRecovery"] = ledger_calls(doc)
            report["completedAt"] = time.time()
            save()
            print(json.dumps({"documentId": doc, "status": "INDEXED", "explicitRetryCalls": 0}), flush=True)
            return
        if current["pipelineStatus"] == "FAILED":
            raise RuntimeError("Recovery reached FAILED; inspect the saved document error")
        time.sleep(2)
    raise RuntimeError("Lease recovery did not complete within 450 seconds")


if __name__ == "__main__":
    main()
