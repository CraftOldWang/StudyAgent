"""Real local S3/MySQL/Redis upload acceptance with authored bytes and a process restart."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import time

import requests


def sql(query):
    return subprocess.check_output(["docker", "exec", "-e", "MYSQL_PWD=root", "study-agent-mysql", "mysql",
                                    "-uroot", "-D", "study_agent_upload_eval", "-N", "-e", query], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--payload", type=Path, default=Path(".eval/upload-payloads/smoke-20mb.bin"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    env = dict(value.split("=", 1) for value in json.loads(subprocess.check_output(
        ["docker", "inspect", "study-agent-eval-app-1"]))[0]["Config"]["Env"])
    assert "/study_agent_upload_eval?" in env["SPRING_DATASOURCE_URL"]
    config = json.loads(env["SPRING_APPLICATION_JSON"])
    assert config["rocketmq"]["consumer"]["listeners"]["study-agent-upload-benchmark-consumer"]["study-agent-upload-benchmark"] is False
    report = {"status": "RUNNING", "checks": [], "fixtures": {}}

    def request(method, path, user=1, expected=True, **kwargs):
        response = requests.request(method, "http://127.0.0.1:8080" + path,
                                    headers={"X-User-Id": str(user)}, timeout=180, **kwargs)
        body = response.json()
        if expected:
            assert response.ok and body["code"] == 0, (response.status_code, body)
            return body.get("data")
        assert not response.ok or body["code"] != 0, body
        return {"httpStatus": response.status_code, "body": body}

    def persist():
        (args.output / "acceptance.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    def check(name, evidence=True):
        report["checks"].append({"name": name, "evidence": evidence})
        persist()
        print(name, flush=True)

    def init(kb, payload, declared_hash=None):
        return request("POST", "/api/files/multipart/init", data={
            "knowledgeBaseId": kb, "filename": "synthetic-throughput.pdf", "contentType": "application/pdf",
            "sha256": declared_hash or hashlib.sha256(payload).hexdigest(), "fileSize": len(payload),
            "chunkSize": 8388608, "totalChunks": (len(payload) - 1) // 8388608 + 1})

    def part(session, index, payload):
        return request("POST", f"/api/files/multipart/{session}/chunks/{index}",
                       files={"chunk": ("part.bin", payload, "application/octet-stream")})

    def complete(session, kb, expected=True):
        return request("POST", "/api/files/multipart/complete", expected=expected,
                       data={"uploadSessionId": session, "knowledgeBaseId": kb})

    def calls():
        return sum(json.loads(line).get("status") == "STARTED" for line in
                   Path(".eval/model-calls.jsonl").read_text(encoding="utf-8").splitlines() if line.strip())

    before = calls()
    try:
        payload = args.payload.read_bytes()
        kb = request("POST", "/api/knowledge-bases", json={"name": "Native upload acceptance " + str(time.time_ns())})["id"]
        session = init(kb, payload)["uploadSessionId"]
        report["fixtures"].update({"knowledgeBaseId": kb, "uploadSessionId": session,
                                    "payloadBytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
        persist()
        part(session, 0, payload[:8388608])
        part(session, 0, payload[:8388608])
        assert sql(f"SELECT COUNT(*) FROM upload_parts WHERE upload_session_id={session}") == "1"
        check("duplicate_part_has_one_durable_etag")
        subprocess.run(["docker", "exec", "study-agent-redis", "redis-cli", "-n", "2", "DEL", f"upload:bitmap:{session}"], check=True, capture_output=True)
        status = request("GET", f"/api/files/multipart/{session}")
        assert status["uploadedChunkIndexes"] == [0]
        check("bitmap_loss_recovers_from_mysql", status)

        subprocess.run(["docker", "restart", "study-agent-eval-app-1"], check=True, capture_output=True)
        deadline = time.monotonic() + 90
        while True:
            try:
                request("GET", "/api/knowledge-bases")
                break
            except requests.RequestException:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        resumed = init(kb, payload)
        assert resumed["uploadSessionId"] == session and resumed["uploadedChunks"] == 1
        missing = request("GET", f"/api/files/multipart/{session}")["missingChunkIndexes"]
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(part, session, index, payload[index * 8388608:(index + 1) * 8388608]) for index in missing]
            for future in futures:
                future.result()
        check("process_restart_resumes_only_missing_bytes", {"missingIndexes": missing,
                "resumedBytes": sum(len(payload[index * 8388608:(index + 1) * 8388608]) for index in missing),
                "retainedBytes": 8388608})
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: complete(session, kb), range(2)))
        assert len({result["documentId"] for result in results}) == 1
        assert len({result["fileId"] for result in results}) == 1
        report["fixtures"]["documentId"] = results[0]["documentId"]
        check("concurrent_complete_returns_one_file_and_document", results)
        duplicated = init(kb, payload)
        assert duplicated["duplicated"] and duplicated["documentId"] == results[0]["documentId"]
        assert sql(f"SELECT COUNT(*) FROM file_records WHERE knowledge_base_id={kb}") == "1"
        assert sql(f"SELECT COUNT(*) FROM documents WHERE knowledge_base_id={kb}") == "1"
        check("repeated_init_reuses_existing_document", duplicated)
        check("other_user_rejected", request("GET", f"/api/files/multipart/{session}", user=2, expected=False))
        other = request("POST", "/api/knowledge-bases", json={"name": "Native upload scope check"})["id"]
        check("other_knowledge_base_rejected", complete(session, other, expected=False))

        wrong = init(kb, b"deliberately wrong", "a" * 64)["uploadSessionId"]
        report["fixtures"]["wrongHashSession"] = wrong
        part(wrong, 0, b"deliberately wrong")
        error = complete(wrong, kb, expected=False)
        status = request("GET", f"/api/files/multipart/{wrong}")
        assert status["status"] == "HASH_FAILED"
        assert sql(f"SELECT COUNT(*) FROM documents WHERE knowledge_base_id={kb}") == "1"
        check("hash_mismatch_does_not_publish", error)
        request("POST", f"/api/files/multipart/{wrong}/cancel")
        request("POST", f"/api/files/multipart/{wrong}/cancel")
        assert request("GET", f"/api/files/multipart/{wrong}")["status"] == "CANCELLED"
        check("cancel_failed_object_and_repeat_cancel")

        absent = init(kb, b"missing")["uploadSessionId"]
        check("missing_parts_rejected", complete(absent, kb, expected=False))
        request("POST", f"/api/files/multipart/{absent}/cancel")
        check("cancel_incomplete_native_upload")
        assert calls() == before
        check("no_model_calls", {"before": before, "after": calls()})
        assert sql("SELECT COUNT(*) FROM document_chunks") == "0"
        report["status"] = "SUCCEEDED"
    except Exception as exc:
        report["status"] = "FAILED"
        report["error"] = str(exc)
        raise
    finally:
        persist()


if __name__ == "__main__":
    main()
