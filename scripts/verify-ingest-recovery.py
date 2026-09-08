"""Real API fault test against an explicitly named eval index; restore write settings in finally."""
import argparse
import hashlib
import json
import time
from pathlib import Path

import requests


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--index", required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--es", default="http://localhost:9200")
    args = parser.parse_args()
    if not args.index.startswith("eval-"):
        raise ValueError("Fault injection requires an isolated eval- index")
    args.run_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.run_dir / "report.json"
    if report_path.exists():
        raise RuntimeError("A report already exists; inspect it before starting another mutation")
    report = {"index": args.index, "file": str(args.file.resolve()), "events": []}

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def api(method, path, **kwargs):
        response = requests.request(method, args.api + path, headers={"X-User-Id": "1"}, timeout=180, **kwargs)
        body = response.json()
        report["events"].append({"method": method, "path": path, "status": response.status_code,
                                 "traceId": response.headers.get("X-Trace-Id"), "at": time.time()})
        save()
        response.raise_for_status()
        if not body.get("success", body.get("code") in (0, 200)):
            raise RuntimeError(body)
        return body["data"]

    def es(method, path, **kwargs):
        response = requests.request(method, args.es + path, timeout=20, **kwargs)
        response.raise_for_status()
        return response.json()

    def wait_status(kb, doc, target):
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            documents = api("GET", f"/api/knowledge-bases/{kb}/documents")
            found = next(row for row in documents if str(row["id"]) == str(doc))
            if found["pipelineStatus"] == target:
                return found
            time.sleep(0.5)
        raise RuntimeError(f"Document did not reach {target}")

    def document_calls(doc):
        rows = [json.loads(line) for line in Path(".eval/embedding-calls.jsonl").read_text(encoding="utf-8").splitlines()]
        return [row["callId"] for row in rows if row.get("operation") == f"INGEST /documents/{doc}"
                and row.get("status") == "STARTED"]

    settings = es("GET", f"/{args.index}/_settings?flat_settings=true")
    original = settings[args.index]["settings"].get("index.blocks.write")
    report["originalWriteBlock"] = original
    save()
    kb = api("POST", "/api/knowledge-bases", json={"name": "Recovery fault " + args.run_dir.name})["id"]
    report["knowledgeBaseId"] = kb
    save()
    try:
        es("PUT", f"/{args.index}/_settings", json={"index.blocks.write": True})
        report["writeBlockEnabled"] = True
        save()
        with args.file.open("rb") as source:
            report["sha256"] = hashlib.file_digest(source, "sha256").hexdigest()
            source.seek(0)
            result = api("POST", "/api/files/upload", data={"knowledgeBaseId": kb},
                         files={"file": (args.file.name, source, "application/pdf")})
        doc = result["documentId"]
        report["documentId"] = doc
        report["failed"] = wait_status(kb, doc, "FAILED")
        report["documentCallIdsBeforeRecovery"] = document_calls(doc)
        save()
    finally:
        es("PUT", f"/{args.index}/_settings", json={"index.blocks.write": original})
        report["writeBlockRestored"] = True
        save()
    # One request only. MQ may race this explicit retry, which remains visible in the recorded result.
    report["retry"] = api("POST", f"/api/documents/{doc}/retry")
    report["recovered"] = wait_status(kb, doc, "INDEXED")
    report["documentCallIdsAfterRecovery"] = document_calls(doc)
    report["noRepeatedDocumentEmbedding"] = (
        report["documentCallIdsBeforeRecovery"] == report["documentCallIdsAfterRecovery"])
    save()
    if not report["noRepeatedDocumentEmbedding"]:
        raise AssertionError("Recovery invoked additional DOCUMENT embeddings")
    print(json.dumps({"documentId": doc, "status": "INDEXED", "noRepeatedDocumentEmbedding": True}, ensure_ascii=False))


if __name__ == "__main__":
    main()
