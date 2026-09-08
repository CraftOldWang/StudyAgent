"""Resumable real planning API acceptance; inputs reference a separate production-ingested corpus."""
import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["create", "execute", "view", "session"])
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--corpus-run", type=Path, default=Path(".eval/runs/m5-planning-corpus-v1"))
    parser.add_argument("--manifest", type=Path, default=Path("eval/planning/corpus-v1.json"))
    parser.add_argument("--goal", default="面向编译原理期末考试，把所选课件归纳为五个循序渐进的知识点，参考习题安排重点和练习时间，保留基础概念。")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    headers = {"X-User-Id": "1"}
    base = "http://localhost:8080"
    state_file = args.run_dir / "state.json"
    state = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}

    def request(method, path, label, **kwargs):
        event = {"method": method, "path": path, "at": time.time()}
        output = args.run_dir / (label + "-" + str(time.time_ns()) + ".json")
        output.write_text(json.dumps(event, indent=2), encoding="utf-8")
        started = time.perf_counter()
        try:
            response = requests.request(method, base + path, headers=headers, timeout=(10, 900), **kwargs)
            event.update(status=response.status_code, traceId=response.headers.get("X-Trace-Id"), response=response.json())
            response.raise_for_status()
            if event["response"]["code"] != 0:
                raise RuntimeError("Planning API failure; raw response preserved")
            return event["response"]["data"]
        except requests.RequestException as error:
            event["transportError"] = type(error).__name__
            raise
        finally:
            event["elapsedMillis"] = (time.perf_counter() - started) * 1000
            output.write_text(json.dumps(event, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.stage == "create":
        if state.get("runId"):
            raise RuntimeError("Run already exists; execute or inspect the recorded ID")
        corpus = json.loads((args.corpus_run / "ingest-state.json").read_text(encoding="utf-8"))
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        payload = {"knowledgeBaseId": corpus["knowledgeBases"]["compiler"], "learningGoal": args.goal, "targetPointCount": 5,
                   "lessonDocumentIds": [corpus["documents"][f["id"]]["documentId"] for f in manifest["files"] if f["planningRole"] == "LESSON"],
                   "exerciseDocumentIds": [corpus["documents"][f["id"]]["documentId"] for f in manifest["files"] if f["planningRole"] == "EXERCISE"]}
        result = request("POST", "/api/learning/plans", "create", json=payload)
        state.update(runId=result["id"], input=payload)
    elif args.stage in {"view", "execute"}:
        path = "/api/learning/plans/" + state["runId"]
        result = request("GET" if args.stage == "view" else "POST", path + ("/execute" if args.stage == "execute" else ""), args.stage)
        state["lastView"] = result
        print(json.dumps({"runId": result["id"], "status": result["status"], "stages": [{"stage": s["stage"], "status": s["status"], "attempt": s["attemptCount"]} for s in result["stages"]]}, ensure_ascii=False))
        if args.stage == "execute":
            assert result["status"] == "SUCCEEDED"
            points = [p for c in result["result"]["outline"]["chapters"] for p in c["points"]]
            tasks = result["result"]["tasks"]
            assert {p["id"] for p in points} == {t["knowledgePointId"] for t in tasks}
            assert len(tasks) == len(points)
    else:
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda i: request("POST", "/api/learning/plans/" + state["runId"] + "/session", "session-" + str(i)), range(2)))
        assert results[0]["id"] == results[1]["id"]
        assert [p["id"] for p in results[0]["plan"]] == [t["knowledgePointId"] for t in state["lastView"]["result"]["tasks"]]
        state["sessionId"] = results[0]["id"]
        print(json.dumps({"sessionId": state["sessionId"], "concurrentCreationDeduplicated": True}))
    state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
