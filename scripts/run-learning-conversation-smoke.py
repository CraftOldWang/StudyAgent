"""Run resumable production API learning turns on an explicitly selected fresh session.

Calls the configured real model and retrieval provider. Does not create a plan,
inject quiz answers, alter state in SQL, or replace a failed request with a new ID.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
import uuid

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--knowledge-base-id", required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--max-points", type=int, default=1)
    parser.add_argument("--disconnect-first-text", action="store_true")
    parser.add_argument("--retry-failed", action="store_true")
    args = parser.parse_args()
    root = args.run_dir; root.mkdir(parents=True, exist_ok=True)
    state_path = root / "state.json"
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {
        "sessionId": args.session_id, "knowledgeBaseId": args.knowledge_base_id, "steps": {}, "status": "RUNNING"}
    assert state["sessionId"] == args.session_id and state["knowledgeBaseId"] == args.knowledge_base_id
    prefix = f"/api/learning/sessions/{args.session_id}"
    headers = {"X-User-Id": "1"}

    def save():
        state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def api(method, path, **kwargs):
        r = requests.request(method, "http://127.0.0.1:8080" + path, headers=headers, timeout=(10, 650), **kwargs)
        body = r.json()
        (root / (str(time.time_ns()) + ".json")).write_text(json.dumps({"path": path, "httpStatus": r.status_code, "body": body}, ensure_ascii=False, indent=2), encoding="utf-8")
        r.raise_for_status(); assert body["code"] == 0, body
        return body["data"]

    def session():
        result = api("GET", prefix)
        assert str(result["knowledgeBaseId"]) == args.knowledge_base_id
        return result

    initial = session()
    if "plan" not in state:
        assert all(p["status"] == "NEW" for p in initial["plan"]), "Start with a fresh session; resume uses its original state file"
        state["plan"] = initial["plan"]; save()
    assert 1 <= args.max_points <= len(state["plan"])

    def turn(point, name, message, expected_status, artifact_type, disconnect=False):
        key = str(point["id"]) + "/" + name
        entry = state["steps"].setdefault(key, {"requestId": str(uuid.uuid4()), "message": message})
        assert entry["message"] == message, "Frozen user script changed"
        save()
        if entry.get("verified"): return entry["turn"]
        history = api("GET", prefix + "/messages")
        existing = next((r for r in history if r["requestId"] == entry["requestId"]), None)
        if existing and existing["status"] == "FAILED" and not args.retry_failed:
            raise RuntimeError("Existing failed request; inspect evidence and explicitly use --retry-failed")
        if existing is None or (existing["status"] == "FAILED" and args.retry_failed):
            entry["startedAt"] = datetime.now(timezone.utc).isoformat(); save()
            body = {"requestId": entry["requestId"], "message": message}
            print("turn", key, flush=True)
            if disconnect:
                event = ""; seen = []
                with requests.post("http://127.0.0.1:8080" + prefix + "/messages/stream", headers=headers, json=body, stream=True, timeout=(10, 650)) as response:
                    response.raise_for_status(); response.encoding = "utf-8"
                    for line in response.iter_lines(decode_unicode=True, chunk_size=1):
                        seen.append(line)
                        if line.startswith("event:"): event = line[6:].strip()
                        if event == "text" and line.startswith("data:"):
                            entry["disconnectedAfterText"] = True; save(); break
                (root / "first-disconnected.sse").write_text("\n".join(seen), encoding="utf-8")
            else:
                result = api("POST", prefix + "/messages", json=body)
                entry["responseTurn"] = result["turn"]; save()
        deadline = time.monotonic() + 650
        while True:
            history = api("GET", prefix + "/messages")
            found = next((r for r in history if r["requestId"] == entry["requestId"]), None)
            if found and found["status"] != "RUNNING": break
            if time.monotonic() > deadline: raise RuntimeError("Original turn still pending; resume without issuing a new request ID")
            time.sleep(3)
        entry["turn"] = found; save()
        assert found["status"] == "SUCCEEDED", found.get("errorMessage")
        artifact = json.loads(found["artifactJson"])
        assert artifact["type"] == artifact_type, artifact
        current = session(); entry["sessionAfter"] = current
        current_point = next(p for p in current["plan"] if p["id"] == point["id"])
        assert current_point["status"] == expected_status, current_point
        if artifact_type == "QUIZ":
            quiz = current["currentQuiz"]
            assert len(quiz["questions"]) == 5 and quiz.get("score") is None and quiz.get("feedback") is None
            assert all("correctAnswer" not in q for q in quiz["questions"])
        if artifact_type == "CARDS":
            assert len(artifact["cards"]) == 3 and len({c["id"] for c in artifact["cards"]}) == 3
            for card in artifact["cards"]:
                assert card.get("sourceChunkId")
                source = api("GET", f"/api/knowledge-bases/{args.knowledge_base_id}/source", params={"chunkId": card["sourceChunkId"]})
                assert source["content"]
        # Repeating a completed request must return the same committed turn and attempt.
        repeated = api("POST", prefix + "/messages", json={"requestId": entry["requestId"], "message": message})["turn"]
        assert repeated["id"] == found["id"] and repeated["attemptCount"] == found["attemptCount"]
        entry["verified"] = True; save()
        print("verified", key, expected_status, flush=True)
        return found

    for i, point in enumerate(state["plan"][:args.max_points]):
        turn(point, "explain", "请从当前知识点开始，结合资料讲清规则、一个例子和一个容易误解的地方。", "EXPLAINING", "EXPLANATION", args.disconnect_first_text and i == 0)
        turn(point, "question", "请再用一个边界情况解释当前知识点，暂时不要出题，也不要进入下一个知识点。", "EXPLAINING", "QUESTION")
        turn(point, "quiz", "我准备好了，请针对当前知识点给我五道选择题进行测验。", "QUIZZING", "QUIZ")
        turn(point, "partial", "我先回答第1题，选A，其他四题暂时没决定。", "QUIZZING", "QUESTION")
        turn(point, "grade", "现在完整提交五题答案：1.A 2.A 3.A 4.A 5.A。", "CARD_GENERATING", "GRADE")
        turn(point, "cards", "请结合本次答题反馈生成当前知识点的三张复习卡，并完成这个知识点。", "COMPLETED", "CARDS")
    final = session()
    assert all(p["status"] == "COMPLETED" for p in final["plan"][:args.max_points])
    if args.disconnect_first_text: assert next(iter(state["steps"].values())).get("disconnectedAfterText"), "No actual text disconnect was observed"
    state.update(status="PASSED_REQUESTED_POINT_FLOW", completedPoints=args.max_points, finalSession=final,
                 limitations=["Functional API/state/source checks; not a learning effectiveness or compression benchmark.", "Fixed A answers intentionally do not target a perfect score."])
    save(); print(state["status"], args.max_points, flush=True)


if __name__ == "__main__":
    main()
