"""Loopback-only ASR worker. Model weights are loaded locally; no audio leaves this host."""
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import time

import ctranslate2
from faster_whisper import WhisperModel


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--processor-version", default="fw-small-536b066-v1")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    cache = args.cache_dir.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    model_hash = hashlib.sha256((args.model_dir / "model.bin").read_bytes()).hexdigest()
    config = {"processorVersion": args.processor_version, "engine": "faster-whisper", "engineVersion": "1.2.1", "runtimeVersion": ctranslate2.__version__,
              "modelSha256": model_hash, "device": "cpu", "computeType": "int8", "cpuThreads": args.threads,
              "beamSize": 5, "vadFilter": True, "conditionOnPreviousText": False, "language": "auto"}
    fingerprint = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()
    print(json.dumps({"stage": "LOADING_MODEL", "config": config}), flush=True)
    model = WhisperModel(str(args.model_dir), device="cpu", compute_type="int8", cpu_threads=args.threads,
                         num_workers=1, local_files_only=True)
    inference_lock = threading.Lock()
    ledger_lock = threading.Lock()

    def event(value):
        with ledger_lock, (cache / "attempts.jsonl").open("a", encoding="utf-8") as out:
            out.write(json.dumps({"at": datetime.now(timezone.utc).isoformat(), **value}, ensure_ascii=False) + "\n")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, code, value):
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                # The completed artifact remains cached if the backend lost its connection.
                pass

        def do_GET(self):
            if self.path == "/health":
                self.reply(200, {"ready": True, "config": config})
            else:
                self.reply(404, {"error": "Unknown endpoint"})

        def do_POST(self):
            if self.path != "/transcribe":
                self.reply(404, {"error": "Unknown endpoint"})
                return
            length = self.headers.get("Content-Length", "")
            expected = self.headers.get("X-Content-Sha256", "")
            if not length.isdigit() or not 0 < int(length) <= 1_073_741_824:
                self.reply(400, {"error": "A known audio/video length within 1 GiB is required"})
                return
            if len(expected) != 64 or any(c not in "0123456789abcdef" for c in expected):
                self.reply(400, {"error": "A SHA-256 input identity is required"})
                return
            name = None
            try:
                with tempfile.NamedTemporaryFile(dir=cache, suffix=".media", delete=False) as out:
                    name = Path(out.name)
                    digest = hashlib.sha256()
                    remaining = int(length)
                    while remaining:
                        data = self.rfile.read(min(1024 * 1024, remaining))
                        if not data:
                            raise ValueError("Media upload ended early")
                        digest.update(data); out.write(data); remaining -= len(data)
                if digest.hexdigest() != expected:
                    raise ValueError("Media SHA-256 mismatch")
                key = hashlib.sha256((fingerprint + expected).encode()).hexdigest()
                result_path = cache / (key + ".json")
                with inference_lock:
                    if result_path.exists():
                        result = json.loads(result_path.read_text(encoding="utf-8"))
                        event({"jobKey": key, "status": "CACHE_HIT"})
                        self.reply(200, {**result, "cacheHit": True})
                        return
                    event({"jobKey": key, "status": "STARTED", "inputSha256": expected})
                    started = time.monotonic()
                    try:
                        segments, info = model.transcribe(str(name), beam_size=5, vad_filter=True,
                                                          condition_on_previous_text=False)
                        rows = [{"start": s.start, "end": s.end, "text": s.text.strip()} for s in segments if s.text.strip()]
                        if not rows:
                            raise ValueError("No speech text was recognized")
                        result = {"processorVersion": args.processor_version, "config": config,
                                  "inputSha256": expected, "durationMs": round(info.duration * 1000),
                                  "durationAfterVadMs": round(info.duration_after_vad * 1000),
                                  "elapsedMs": round((time.monotonic() - started) * 1000),
                                  "language": info.language, "languageProbability": info.language_probability,
                                  "segments": rows, "cacheHit": False}
                        pending = result_path.with_suffix(".pending")
                        pending.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                        os.replace(pending, result_path)
                        event({"jobKey": key, "status": "SUCCEEDED", "elapsedMs": result["elapsedMs"],
                               "durationMs": result["durationMs"], "segments": len(rows)})
                    except Exception as error:
                        event({"jobKey": key, "status": "FAILED", "error": str(error),
                               "elapsedMs": round((time.monotonic() - started) * 1000)})
                        raise
                self.reply(200, result)
            except Exception as error:
                self.reply(422, {"error": str(error)})
            finally:
                if name is not None:
                    name.unlink(missing_ok=True)

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(json.dumps({"ready": True, "port": args.port, "config": config}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
