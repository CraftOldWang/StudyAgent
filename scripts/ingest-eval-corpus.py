"""Upload the frozen source manifest through production APIs, resuming by recorded document IDs."""
import argparse
import hashlib
import json
import mimetypes
import sys
import time
from pathlib import Path
import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path("eval/rag/corpus-v1.json"))
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--courses", nargs="+", default=["algorithms", "os", "compiler"])
    args = parser.parse_args()
    corpus = json.loads(args.manifest.read_text(encoding="utf-8"))
    fingerprint = hashlib.sha256(args.manifest.read_bytes()).hexdigest()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    state_path = args.run_dir / "ingest-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {
        "manifestSha256": fingerprint, "knowledgeBases": {}, "documents": {}}
    if state["manifestSha256"] != fingerprint:
        raise RuntimeError("Source manifest changed during a run")
    session = requests.Session()
    session.headers["X-User-Id"] = "1"

    def save():
        state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def request(method, path, **kwargs):
        event = {"method": method, "path": path, "at": time.time()}
        start = time.perf_counter()
        try:
            response = session.request(method, "http://localhost:8080" + path, timeout=(10, 180), **kwargs)
            event.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
            response.raise_for_status()
            if event["response"]["code"] != 0:
                raise RuntimeError("Application failure; see HTTP log")
            return event["response"]["data"]
        finally:
            event["elapsedMillis"] = (time.perf_counter() - start) * 1000
            with (args.run_dir / "ingest-http.jsonl").open("a", encoding="utf-8") as output:
                output.write(json.dumps(event, ensure_ascii=False) + "\n")

    for source in corpus["files"]:
        if source["course"] not in args.courses:
            continue
        course = source["course"]
        if course not in state["knowledgeBases"]:
            name = "Corpus " + args.run_dir.name + " " + course
            existing = [kb for kb in request("GET", "/api/knowledge-bases") if kb["name"] == name]
            kb = existing[0] if existing else request("POST", "/api/knowledge-bases", json={"name": name})
            state["knowledgeBases"][course] = kb["id"]
            save()
        kb = state["knowledgeBases"][course]
        if source["id"] not in state["documents"]:
            file = Path(corpus["sourceRoot"]) / source["relativePath"]
            with file.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != source["sha256"]:
                    raise RuntimeError("Source file differs from frozen hash: " + source["id"])
                # A successful response can be lost. Reconcile by the existing scoped dedup API first.
                dedup = request("GET", "/api/files/dedup", params={"knowledgeBaseId": kb, "sha256": source["sha256"]})
                if dedup.get("duplicated"):
                    raise RuntimeError("Existing upload needs reconciliation before retry: " + source["id"])
                stream.seek(0)
                result = request("POST", "/api/files/upload", data={"knowledgeBaseId": kb},
                                 files={"file": (file.name, stream, mimetypes.guess_type(file.name)[0])})
            state["documents"][source["id"]] = {"documentId": result["documentId"], "fileRecordId": result["fileId"],
                                              "sourceSha256": source["sha256"], "knowledgeBaseId": kb}
            save()
        document = state["documents"][source["id"]]
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            rows = request("GET", f"/api/knowledge-bases/{kb}/documents")
            current = next(row for row in rows if str(row["id"]) == str(document["documentId"]))
            document["lastStatus"] = current
            save()
            if current["pipelineStatus"] == "INDEXED":
                print(json.dumps({"sourceId": source["id"], "documentId": document["documentId"], "status": "INDEXED"}), flush=True)
                break
            if current["pipelineStatus"] == "FAILED":
                raise RuntimeError("Document failed: " + json.dumps(current, ensure_ascii=False))
            time.sleep(1)
        else:
            raise RuntimeError("Document still running; resume this run without reupload")


if __name__ == "__main__":
    main()
