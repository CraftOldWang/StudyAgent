"""Measure real upload APIs using an isolated local synthetic payload, never a model API."""
import argparse
import concurrent.futures
import hashlib
import json
import math
import random
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


def api(base, method, path, **kwargs):
    response = requests.request(method, base + path, headers={"X-User-Id": "1"}, timeout=600, **kwargs)
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 0:
        raise RuntimeError(f"{path}: {body}")
    return body.get("data")


def run(args, concurrency, trial):
    label = f"{args.arm}-c{concurrency}-t{trial}-{time.time_ns()}"
    result = {"label": label, "arm": args.arm, "concurrency": concurrency,
              "trial": trial, "bytes": args.size, "chunkBytes": args.chunk_size,
              "startedAt": datetime.now(timezone.utc).isoformat(), "chunks": []}
    output = args.output / (label + ".json")
    kb = api(args.base, "POST", "/api/knowledge-bases", json={"name": label})
    result["knowledgeBaseId"] = kb["id"]
    started = time.perf_counter()
    try:
        digest = hashlib.sha256()
        with args.payload.open("rb") as source:
            while block := source.read(1024 * 1024):
                digest.update(block)
        result["sha256"] = digest.hexdigest()
        result["hashSeconds"] = time.perf_counter() - started
        total = math.ceil(args.size / args.chunk_size)
        phase = time.perf_counter()
        init = api(args.base, "POST", "/api/files/multipart/init", data={
            "knowledgeBaseId": kb["id"], "filename": "synthetic-throughput.pdf",
            "contentType": "application/pdf", "sha256": digest.hexdigest(),
            "fileSize": args.size, "chunkSize": args.chunk_size, "totalChunks": total})
        result["initSeconds"] = time.perf_counter() - phase
        result["init"] = init
        if init["duplicated"]:
            raise RuntimeError("Deduplication fast path invalidates this throughput trial")
        session = init["uploadSessionId"]

        def upload(index):
            part_start = time.perf_counter()
            with args.payload.open("rb") as source:
                source.seek(index * args.chunk_size)
                chunk = source.read(args.chunk_size)
            api(args.base, "POST", f"/api/files/multipart/{session}/chunks/{index}",
                files={"chunk": (f"{index}.part", chunk, "application/octet-stream")})
            return {"index": index, "bytes": len(chunk), "seconds": time.perf_counter() - part_start}

        phase = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(upload, index) for index in range(total)]
            failures = []
            for future in concurrent.futures.as_completed(futures):
                try:
                    result["chunks"].append(future.result())
                except Exception as exc:
                    failures.append(str(exc))
        result["uploadSeconds"] = time.perf_counter() - phase
        if failures:
            result["chunkFailures"] = failures
            raise RuntimeError(f"{len(failures)} chunks failed: {failures[0]}")
        phase = time.perf_counter()
        status = api(args.base, "GET", f"/api/files/multipart/{session}")
        result["statusSeconds"] = time.perf_counter() - phase
        result["beforeComplete"] = status
        if status["missingChunkIndexes"]:
            raise RuntimeError("Server reports missing chunks")
        phase = time.perf_counter()
        result["complete"] = api(args.base, "POST", "/api/files/multipart/complete",
                                 data={"knowledgeBaseId": kb["id"], "uploadSessionId": session})
        result["completeSeconds"] = time.perf_counter() - phase
        result["status"] = "SUCCEEDED"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = str(exc)
        raise
    finally:
        result["totalSeconds"] = time.perf_counter() - started
        result["chunks"].sort(key=lambda chunk: chunk["index"])
        output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({key: result.get(key) for key in
                          ("label", "status", "totalSeconds", "uploadSeconds", "completeSeconds")}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--arm", required=True)
    parser.add_argument("--payload", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--size", type=int, default=400_000_000)
    parser.add_argument("--chunk-size", type=int, default=8 * 1024 * 1024)
    parser.add_argument("--trials", type=int, default=5)
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 4])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if not args.payload.exists():
        args.payload.parent.mkdir(parents=True, exist_ok=True)
        generator = random.Random(20260909)
        with args.payload.open("wb") as payload:
            remaining = args.size
            while remaining:
                block = generator.randbytes(min(1024 * 1024, remaining))
                payload.write(block)
                remaining -= len(block)
    if args.payload.stat().st_size != args.size:
        raise ValueError("Payload size differs from requested size")
    # Require the dedicated environment and disabled consumer before creating any upload.
    container = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(item.split("=", 1) for item in container["Config"]["Env"])
    config = json.loads(env["SPRING_APPLICATION_JSON"])
    assert "/study_agent_upload_eval?" in env["SPRING_DATASOURCE_URL"]
    assert env["STUDY_AGENT_OBJECT_STORAGE_BUCKET"] == "study-agent-upload-eval"
    assert config["rocketmq"]["consumer"]["listeners"]["study-agent-upload-benchmark-consumer"]["study-agent-upload-benchmark"] is False
    manifest = {"arm": args.arm, "payloadKind": "seeded random bytes; PDF extension only; parser disabled",
                "measurement": "client hash through persisted upload and real MQ enqueue; excludes parsing/indexing",
                "git": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "jarSha256": hashlib.file_digest(Path("target/study-agent-0.0.1-SNAPSHOT.jar").open("rb"), "sha256").hexdigest(),
                "size": args.size, "trials": args.trials, "concurrency": args.concurrency,
                "chunkSize": args.chunk_size, "jvm": env.get("JAVA_TOOL_OPTIONS")}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    for trial in range(1, args.trials + 1):
        order = args.concurrency if trial % 2 else list(reversed(args.concurrency))
        for concurrency in order:
            run(args, concurrency, trial)


if __name__ == "__main__":
    main()
