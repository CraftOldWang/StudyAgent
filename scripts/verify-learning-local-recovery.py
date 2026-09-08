"""Real MySQL/API stage recovery using authored fixtures; no model or retrieval acceptance claim."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid
from urllib.parse import urlparse

import requests


def encoded(value):
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=root", "study-agent-mysql", "mysql",
                             "--default-character-set=utf8mb4", "--raw", "-uroot", "-D", "study_agent_eval", "-N"],
                            input=statement.encode("utf-8"), capture_output=True, check=True)
    return result.stdout.decode("utf-8").strip()


def calls():
    path = Path(".eval/model-calls.jsonl")
    return sum(json.loads(line)["status"] == "STARTED" for line in path.read_text(encoding="utf-8").splitlines() if line.strip())


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--run-dir", default=".eval/runs/m5-local-recovery-v1")
    args = parser.parse_args()
    assert urlparse(args.base_url).hostname in {"127.0.0.1", "localhost"}, "Only the local evaluation application is allowed"
    root = Path(args.run_dir); root.mkdir(parents=True, exist_ok=True)
    state_file = root / "fixture.json"
    headers = {"X-User-Id": "1"}
    before = calls()

    def request(method, path, body=None, user=1):
        response = requests.request(method, args.base_url + path, json=body, headers={"X-User-Id": str(user)}, timeout=30)
        return {"httpStatus": response.status_code, "body": response.json()}

    if state_file.exists():
        fixture = json.loads(state_file.read_text(encoding="utf-8"))
    else:
        kb = request("POST", "/api/knowledge-bases", {"name": "M5 synthetic recovery " + root.name})
        assert kb["body"]["code"] == 0, kb
        base = 6_000_000_000_000_000_000 + (uuid.uuid4().int % 100_000_000_000) * 100
        fixture = {"kind": "authored synthetic data; precommitted artifacts; NO model acceptance",
                   "sessionId": base, "pointId": base + 1, "turnId": base + 2, "knowledgeBaseId": int(kb["body"]["data"]["id"]),
                   "traceId": str(uuid.uuid4()), "requestId": str(uuid.uuid4()), "agentSession": str(uuid.uuid4()),
                   "message": "Complete the precommitted synthetic cards without a model call."}
        state_file.write_text(json.dumps(fixture, indent=2), encoding="utf-8")
        sid, pid, tid, kid = (fixture[k] for k in ["sessionId", "pointId", "turnId", "knowledgeBaseId"])
        context = {"user_id": "1", "session_id": fixture["agentSession"], "context": [
            {"id": str(uuid.uuid4()), "name": "fixture", "role": "USER", "content": [{"type": "text", "text": "Authored synthetic fact and cards, already committed."}],
             "metadata": {"learningPointId": str(pid), "learningTurnId": str(tid)}}]}
        cards = [{"id": str(base + 10 + i), "front": f"Synthetic question {i}", "back": f"Synthetic answer {i}", "sourceChunkId": "synthetic-source"} for i in range(3)]
        artifact = {"type": "CARDS", "knowledgePointId": str(pid), "cards": cards}
        statement = f"""
        START TRANSACTION;
        INSERT INTO learning_sessions(id,user_id,knowledge_base_id,learning_goal,agentscope_session_id,status,active_knowledge_point_id,active_turn_id,created_at,updated_at)
          VALUES({sid},1,{kid},'Synthetic recovery only',{encoded(fixture['agentSession'])},'ACTIVE',{pid},{tid},UTC_TIMESTAMP(),UTC_TIMESTAMP());
        INSERT INTO knowledge_points(id,session_id,user_id,sequence_no,topic,subtopics_json,estimated_minutes,status,created_at,updated_at)
          VALUES({pid},{sid},1,1,'Synthetic fixture','[]',1,'CARD_GENERATING',UTC_TIMESTAMP(),UTC_TIMESTAMP());
        INSERT INTO learning_contexts(session_id,user_id,agent_state_json,compression_strategy,updated_at)
          VALUES({sid},1,{encoded(context)},'THRESHOLD',UTC_TIMESTAMP());
        INSERT INTO learning_turns(id,user_id,session_id,knowledge_point_id,request_id,input_hash,user_message,assistant_message,artifact_json,context_delta_json,prepared_context_json,
                                  status,phase,processing_token,lease_until,trace_id,attempt_count,created_at,updated_at)
          VALUES({tid},1,{sid},{pid},{encoded(fixture['requestId'])},{encoded(hashlib.sha256(fixture['message'].encode()).hexdigest())},{encoded(fixture['message'])},
                 'Synthetic cards committed',{encoded(artifact)},{encoded(context)},{encoded(context)},'RUNNING','ARTIFACTS_COMMITTED',
                 {encoded(str(uuid.uuid4()))},DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR),{encoded(fixture['traceId'])},1,UTC_TIMESTAMP(),UTC_TIMESTAMP());
        """
        for card in cards:
            statement += f"INSERT INTO review_cards(id,user_id,knowledge_point_id,knowledge_base_id,front,back,source_chunk_id,created_at) VALUES({card['id']},1,{pid},{kid},{encoded(card['front'])},{encoded(card['back'])},'synthetic-source',UTC_TIMESTAMP());\n"
        statement += "COMMIT;"
        (root / "fixture.sql").write_text(statement, encoding="utf-8")
        sql(statement)
    sid, pid, tid = (fixture[k] for k in ["sessionId", "pointId", "turnId"])
    preflight = sql(f"SELECT status,phase FROM learning_turns WHERE id={tid} AND session_id={sid};")
    assert preflight in {"RUNNING\tARTIFACTS_COMMITTED", "FAILED\tARTIFACTS_COMMITTED", "SUCCEEDED\tCOMPLETED"}, preflight
    body = {"requestId": fixture["requestId"], "message": fixture["message"]}
    path = f"/api/learning/sessions/{sid}/messages"
    with ThreadPoolExecutor(max_workers=2) as pool:
        concurrent = list(pool.map(lambda _: request("POST", path, body), range(2)))
    (root / "concurrent.json").write_text(json.dumps(concurrent, ensure_ascii=False, indent=2), encoding="utf-8")
    assert all(r["body"]["code"] == 0 for r in concurrent), concurrent
    final = request("GET", f"/api/learning/sessions/{sid}/turns/{tid}")
    assert final["body"]["data"]["status"] == "SUCCEEDED", final
    assert final["body"]["data"]["attemptCount"] == 2, final
    repeated = request("POST", path, body)
    conflict = request("POST", path, {**body, "message": "different input"})
    denied = request("GET", path, user=2)
    assert repeated["body"]["data"]["turn"]["status"] == "SUCCEEDED", repeated
    assert conflict["body"]["code"] == 409, conflict
    assert denied["body"]["code"] == 404, denied
    history = request("GET", path)
    assert len(history["body"]["data"]) == 1, history
    assert not ({"preparedContextJson", "contextDeltaJson", "processingToken"} & history["body"]["data"][0].keys()), history
    database = sql(f"SELECT status,IFNULL(active_knowledge_point_id,'NULL'),IFNULL(active_turn_id,'NULL') FROM learning_sessions WHERE id={sid}; SELECT status FROM knowledge_points WHERE id={pid}; SELECT COUNT(*) FROM review_cards WHERE knowledge_point_id={pid}; SELECT phase,status,attempt_count FROM learning_turns WHERE id={tid};")
    assert database.splitlines() == ["COMPLETED\tNULL\tNULL", "COMPLETED", "3", "COMPLETED\tSUCCEEDED\t2"], database
    stream = requests.post(args.base_url + path + "/stream", json=body, headers=headers, timeout=30)
    (root / "sse.txt").write_text(stream.text, encoding="utf-8")
    assert "event:accepted" in stream.text and "event:result" in stream.text and "SUCCEEDED" in stream.text, stream.text
    after = calls()
    assert before == after, f"Unexpected model attempt: {before} -> {after}"
    result = {"verifiedAt": datetime.now(timezone.utc).isoformat(), "scope": fixture["kind"], "fixture": fixture,
              "callsBefore": before, "callsAfter": after, "concurrent": concurrent, "final": final, "repeat": repeated,
              "conflictingInput": conflict, "unownedSession": denied, "history": history, "database": database,
              "sseCompletedReplay": True, "limitations": ["Artifacts were inserted as a synthetic post-commit fixture.",
                "Lease expiry was preconfigured; no process was killed.", "No real model, retrieval, semantic quality or mid-generation disconnect acceptance."]}
    (root / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": "PASSED", "sessionId": str(sid), "modelCallsAdded": after-before, "result": str(root / 'result.json')}))


if __name__ == "__main__":
    main()
