"""Real planning and two learning turns over an already ingested media document.

Does not transcribe again, seed learning state, or replace failed request IDs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

import requests


CASES = {
    "audio": {
        "knowledgeBaseId": "2097501531286913026", "documentId": "2097501536244580353",
        "goal": "学习这份课堂录音中的支配关系与循环分析，归纳为一个知识点。",
        "question": "请根据录音解释D支配n与直接支配者的区别，给出一个小例子并引用转写来源。转写有误或没有讲到的部分请明确指出，暂时不要出题。",
    },
    "video": {
        "knowledgeBaseId": "2097504440811696129", "documentId": "2097504990227771393",
        "goal": "学习这段复习视频中的希尔排序，归纳为一个知识点。",
        "question": "这段视频说希尔排序又叫什么？它怎样分组并缩小增量？请引用转写来源，区分视频中说明的内容和补充解释，暂时不要出题。",
    },
}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", choices=CASES, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--retry-failed", action="store_true")
    args = parser.parse_args()
    case = CASES[args.case]
    inspected = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(v.split("=", 1) for v in inspected["Config"]["Env"])
    assert env.get("STUDY_AGENT_ELASTICSEARCH_PHYSICAL_INDEX") == "eval-m8-asr"
    root = args.run_dir; root.mkdir(parents=True, exist_ok=True)
    file = root / "state.json"
    state = json.loads(file.read_text(encoding="utf-8")) if file.exists() else {"case": case, "steps": {}}
    assert state["case"] == case

    def save():
        file.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def api(method, path, **kwargs):
        r = requests.request(method, "http://127.0.0.1:8080" + path, headers={"X-User-Id": "1"}, timeout=(10, 900), **kwargs)
        body = r.json()
        (root / (str(time.time_ns()) + "-api.json")).write_text(json.dumps({"method": method, "path": path, "status": r.status_code, "body": body}, ensure_ascii=False, indent=2), encoding="utf-8")
        r.raise_for_status(); assert body["code"] == 0, body
        return body["data"]

    documents = api("GET", f"/api/knowledge-bases/{case['knowledgeBaseId']}/documents")
    assert any(d["id"] == case["documentId"] and d["pipelineStatus"] == "INDEXED" for d in documents)
    if "runId" not in state:
        plan = api("POST", "/api/learning/plans", json={"knowledgeBaseId": case["knowledgeBaseId"],
            "learningGoal": case["goal"], "targetPointCount": 1, "lessonDocumentIds": [case["documentId"]], "exerciseDocumentIds": []})
        state["runId"] = plan["id"]; save()
    if state.get("plan", {}).get("status") != "SUCCEEDED":
        if state.get("plan", {}).get("status") == "FAILED" and not args.retry_failed:
            raise RuntimeError("Inspect original failed plan before explicit retry")
        state["plan"] = api("POST", f"/api/learning/plans/{state['runId']}/execute"); save()
        assert state["plan"]["status"] == "SUCCEEDED", state["plan"].get("errorMessage")
    if "sessionId" not in state:
        session = api("POST", f"/api/learning/plans/{state['runId']}/session")
        assert len(session["plan"]) == 1 and session["plan"][0]["status"] == "NEW"
        state["sessionId"] = session["id"]; state["initialSession"] = session; save()
    prefix = f"/api/learning/sessions/{state['sessionId']}"
    for name, message, kind in [
        ("explain", "请从当前知识点开始，结合这份音视频转写资料讲清核心规则和一个例子，并引用来源。", "EXPLANATION"),
        ("question", case["question"], "QUESTION"),
    ]:
        step = state["steps"].setdefault(name, {"requestId": str(uuid.uuid4()), "message": message}); save()
        assert step["message"] == message
        if step.get("verified"): continue
        found = next((t for t in api("GET", prefix + "/messages") if t["requestId"] == step["requestId"]), None)
        if found and found["status"] == "FAILED" and not args.retry_failed:
            raise RuntimeError("Inspect original failed turn before explicit retry")
        if found is None or (found["status"] == "FAILED" and args.retry_failed):
            print(name, state["sessionId"], flush=True)
            result = api("POST", prefix + "/messages", json={"requestId": step["requestId"], "message": message})
            found = result["turn"]
        deadline = time.monotonic() + 650
        while found["status"] == "RUNNING":
            assert time.monotonic() < deadline, "Resume pending original request"
            time.sleep(3)
            found = api("GET", prefix + "/turns/" + found["id"])
        step["turn"] = found; save()
        assert found["status"] == "SUCCEEDED", found.get("errorMessage")
        assert json.loads(found["artifactJson"])["type"] == kind
        current = api("GET", prefix)
        assert current["plan"][0]["status"] == "EXPLAINING"
        refs = sorted(set(re.findall(r"\b[a-f0-9]{64}\b", found["assistantMessage"])))
        assert refs, "Response lacks explicit source references; inspect the actual answer"
        for ref in refs:
            source = api("GET", f"/api/knowledge-bases/{case['knowledgeBaseId']}/source", params={"chunkId": ref})
            assert source["content"] and re.search(r"\d{2}:\d{2}:\d{2}\.\d{3}", source["content"])
        repeated = api("POST", prefix + "/messages", json={"requestId": step["requestId"], "message": message})["turn"]
        assert repeated["id"] == found["id"] and repeated["attemptCount"] == found["attemptCount"]
        step.update(verified=True, sourceChunkIds=refs, sessionAfter=current); save()
    state.update(status="API_VERIFIED_SEMANTIC_REVIEW_PENDING", jarSha256=hashlib.sha256(Path("target/study-agent-0.0.1-SNAPSHOT.jar").read_bytes()).hexdigest()); save()
    print(json.dumps({"case": args.case, "sessionId": state["sessionId"], "status": state["status"]}), flush=True)


if __name__ == "__main__":
    main()
