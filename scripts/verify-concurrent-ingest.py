"""Exercise concurrent new-document chunk writes through real upload/MQ APIs; never substitute mock providers."""
import argparse
import hashlib
import json
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    headers = {"X-User-Id": "1"}
    base = "http://localhost:8080"
    kb_file = args.run_dir / "knowledge-base.json"
    if not kb_file.exists():
        response = requests.post(base + "/api/knowledge-bases", headers=headers, json={"name": "Concurrent ingest " + args.run_dir.name}, timeout=20)
        response.raise_for_status()
        assert response.json()["code"] == 0
        kb_file.write_text(json.dumps(response.json(), ensure_ascii=False, indent=2), encoding="utf-8")
    kb = json.loads(kb_file.read_text(encoding="utf-8"))["data"]["id"]
    source = Path(".eval/m4-derived-lesson.txt").read_text(encoding="utf-8")
    documents = []
    for wave in range(2):
        pending = [i for i in range(wave * 4, wave * 4 + 4) if not (args.run_dir / f"upload-{i}.json").exists()]
        barrier = threading.Barrier(len(pending)) if pending else None

        def upload(i):
            extension = "md" if i % 2 else "txt"
            payload = (f"# 并发文档 {i}\n\n" + source).encode()
            name = f"concurrent-{i}.{extension}"
            sha = hashlib.sha256(payload).hexdigest()
            dedup = requests.get(base + "/api/files/dedup", headers=headers,
                                 params={"knowledgeBaseId": kb, "sha256": sha}, timeout=15)
            dedup.raise_for_status()
            if dedup.json()["data"].get("duplicated"):
                raise RuntimeError("An unrecorded upload exists; reconcile it before retry")
            row = {"case": i, "file": name, "sha256": sha, "bytes": len(payload), "wave": wave}
            barrier.wait(timeout=20)
            started = time.perf_counter()
            try:
                response = requests.post(base + "/api/files/upload", headers=headers, data={"knowledgeBaseId": kb},
                    files={"file": (name, payload, "text/markdown" if extension == "md" else "text/plain")}, timeout=60)
                row.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
                response.raise_for_status()
                assert row["response"]["code"] == 0
            finally:
                row["elapsedMillis"] = (time.perf_counter() - started) * 1000
                (args.run_dir / f"upload-{i}.json").write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8")

        if pending:
            with ThreadPoolExecutor(max_workers=4) as executor:
                list(executor.map(upload, pending))
        ids = {str(json.loads((args.run_dir / f"upload-{i}.json").read_text(encoding="utf-8"))["response"]["data"]["documentId"])
               for i in range(wave * 4, wave * 4 + 4)}
        deadline = time.monotonic() + 180
        while True:
            response = requests.get(base + f"/api/knowledge-bases/{kb}/documents", headers=headers, timeout=15)
            response.raise_for_status()
            rows = [d for d in response.json()["data"] if str(d["id"]) in ids]
            with (args.run_dir / "status.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(json.dumps({"wave": wave, "at": time.time(), "documents": rows}, ensure_ascii=False) + "\n")
            if any(d["pipelineStatus"] == "FAILED" for d in rows):
                raise RuntimeError("Concurrent ingestion failed; see preserved status and API evidence")
            if len(rows) == 4 and all(d["pipelineStatus"] == "INDEXED" for d in rows):
                documents.extend(rows)
                print(json.dumps({"wave": wave, "indexed": len(rows)}), flush=True)
                break
            if time.monotonic() > deadline:
                raise RuntimeError("Concurrent ingestion did not finish within the observation window")
            time.sleep(1)
    result = {"knowledgeBaseId": kb, "documents": documents, "waves": 2, "uploadsPerWave": 4,
              "httpStatus": "PASSED", "databaseFirstAttemptCheck": "Requires separate attempt_count and InnoDB deadlock-counter verification"}
    (args.run_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"indexed": len(documents), "documentIds": [d["id"] for d in documents]}))


if __name__ == "__main__":
    main()
