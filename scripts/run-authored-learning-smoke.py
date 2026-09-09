"""Separately scoped real-provider smoke using ONLY the two authored fictional fixtures.

Never substitutes for real-course acceptance. `prepare` calls embedding; `plan`
calls the configured learning model. Fresh KB, index and session isolate history.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["prepare", "plan", "session"])
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    root = args.run_dir; root.mkdir(parents=True, exist_ok=True)
    file = root / "state.json"
    state = json.loads(file.read_text(encoding="utf-8")) if file.exists() else {"kind": "authored fictional course, not real-course acceptance"}
    inspected = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(x.split("=", 1) for x in inspected["Config"]["Env"])
    assert env.get("STUDY_AGENT_ELASTICSEARCH_PHYSICAL_INDEX") == "eval-synthetic-learning"
    assert env.get("STUDY_AGENT_ELASTICSEARCH_READ_ALIAS") == "eval-synthetic-learning-read"

    def save():
        file.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def api(method, path, **kwargs):
        r = requests.request(method, "http://127.0.0.1:8080" + path, headers={"X-User-Id": "1"}, timeout=(10, 900), **kwargs)
        body = r.json()
        (root / (str(time.time_ns()) + ".json")).write_text(json.dumps({"method": method, "path": path, "httpStatus": r.status_code, "body": body}, ensure_ascii=False, indent=2), encoding="utf-8")
        r.raise_for_status(); assert body["code"] == 0, body
        return body["data"]

    if args.stage == "prepare":
        if "knowledgeBaseId" not in state:
            state["knowledgeBaseId"] = api("POST", "/api/knowledge-bases", json={"name": "自编纸鹤缓存 · 模型功能验收"})["id"]; save()
        docs = state.setdefault("documents", {})
        for name in ["lesson.md", "exercises.md"]:
            source = Path("eval/synthetic-learning") / name
            content = source.read_bytes(); digest = hashlib.sha256(content).hexdigest()
            assert "纸鹤缓存" in content.decode("utf-8")
            if name not in docs:
                uploaded = api("POST", "/api/files/upload", data={"knowledgeBaseId": state["knowledgeBaseId"]}, files={"file": (name, content, "text/markdown")})
                docs[name] = {"documentId": uploaded["documentId"], "sha256": digest}; save()
            assert docs[name]["sha256"] == digest, "Fixture changed; preserve this run and use a new one"
        deadline = time.monotonic() + 120
        while True:
            rows = api("GET", f"/api/knowledge-bases/{state['knowledgeBaseId']}/documents")
            assert {d["id"] for d in rows} == {d["documentId"] for d in docs.values()}, "Unexpected document in isolated authored KB"
            assert not any(d["pipelineStatus"] == "FAILED" for d in rows), rows
            if all(d["pipelineStatus"] == "INDEXED" for d in rows): break
            if time.monotonic() > deadline: raise RuntimeError("Authored ingest still pending")
            time.sleep(2)
        state["ingested"] = True
    elif args.stage == "plan":
        assert state.get("ingested")
        if "runId" not in state:
            plan = api("POST", "/api/learning/plans", json={"knowledgeBaseId": state["knowledgeBaseId"], "learningGoal": "学习这份自编虚构纸鹤缓存协议，按五个单元形成五个知识点，参考自编习题标记重点。", "targetPointCount": 5,
                "lessonDocumentIds": [state["documents"]["lesson.md"]["documentId"]], "exerciseDocumentIds": [state["documents"]["exercises.md"]["documentId"]]})
            state["runId"] = plan["id"]; save()
        plan = api("POST", f"/api/learning/plans/{state['runId']}/execute")
        state["plan"] = plan; save()
        assert plan["status"] == "SUCCEEDED", "Inspect preserved failure before retry"
        assert len(plan["result"]["tasks"]) == 5
    else:
        assert state.get("plan", {}).get("status") == "SUCCEEDED"
        session = api("POST", f"/api/learning/plans/{state['runId']}/session")
        state["sessionId"] = session["id"]; state["initialSession"] = session
    save()
    print(json.dumps({"stage": args.stage, "state": str(file), "knowledgeBaseId": state.get("knowledgeBaseId"), "runId": state.get("runId"), "sessionId": state.get("sessionId")}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
