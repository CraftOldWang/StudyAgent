"""Seed nine fresh evaluation sessions from one frozen real planning result.

Only initial plans are inserted into the isolated evaluation database. No turns,
answers, cards, summaries or model responses are seeded. All subsequent learning
and strategy configuration use production APIs. This is experiment setup, not
evidence that planning ran nine times.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

import requests


def encoded(value):
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False)
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def seed_sql(replica, blueprint):
    sid = int(replica["sessionId"])
    tasks = copy.deepcopy(blueprint["tasks"])
    for i, task in enumerate(tasks):
        task["knowledgePointId"] = str(sid + i + 1)
    statement = f"""START TRANSACTION;
INSERT INTO learning_sessions(id,user_id,knowledge_base_id,learning_goal,agentscope_session_id,status,active_knowledge_point_id,created_at,updated_at)
VALUES({sid},1,{int(blueprint['knowledgeBaseId'])},{encoded(blueprint['learningGoal'])},{encoded(replica['agentSessionId'])},'ACTIVE',{sid+1},UTC_TIMESTAMP(),UTC_TIMESTAMP());
INSERT INTO learning_plan(id,session_id,user_id,plan_json,created_at)
VALUES({sid+10},{sid},1,{encoded(tasks)},UTC_TIMESTAMP());
"""
    for i, task in enumerate(tasks):
        statement += f"""INSERT INTO knowledge_points(id,session_id,user_id,sequence_no,topic,subtopics_json,estimated_minutes,status,chapter_id,chapter_title,priority,sources_json,created_at,updated_at)
VALUES({sid+i+1},{sid},1,{i+1},{encoded(task['topic'])},{encoded(task['subtopics'])},{int(task['estimatedMinutes'])},'NEW',{int(task['chapterId'])},{encoded(task['chapterTitle'])},{encoded(task['priority'])},{encoded(task['sourceChunkIds'])},UTC_TIMESTAMP(),UTC_TIMESTAMP());
"""
    return statement + "COMMIT;\n"


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--planning-run", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--course", choices=["algorithms", "os"], required=True)
    parser.add_argument("--expected-index", required=True)
    parser.add_argument("--apply", action="store_true", help="Insert fresh initial plans and configure their strategies through the API")
    args = parser.parse_args()
    source = json.loads((args.planning_run / "state.json").read_text(encoding="utf-8"))
    assert source["lastView"]["status"] == "SUCCEEDED", "Require an accepted real planning run"
    tasks = source["lastView"]["result"]["tasks"]
    assert len(tasks) == 5 and len({t["knowledgePointId"] for t in tasks}) == 5
    blueprint = {"sourceRunId": source["runId"], "knowledgeBaseId": source["input"]["knowledgeBaseId"],
                 "learningGoal": source["input"]["learningGoal"], "tasks": tasks, "course": args.course,
                 "expectedIndex": args.expected_index}
    digest = hashlib.sha256(json.dumps(blueprint, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    root = args.run_dir; root.mkdir(parents=True, exist_ok=True)
    state_file = root / "replicas.json"
    if state_file.exists():
        state = json.loads(state_file.read_text(encoding="utf-8"))
        assert state["blueprintSha256"] == digest, "Frozen plan/config changed; never overwrite an experiment"
    else:
        # Rotated strategy order balances provider/time ordering across repeats.
        strategies = ["THRESHOLD", "WHOLE_HISTORY", "LOCAL"]
        replicas = []
        for repeat in range(3):
            for strategy in strategies[repeat:] + strategies[:repeat]:
                sid = 6_200_000_000_000_000_000 + (uuid.uuid4().int % 100_000_000_000) * 100
                replicas.append({"repeat": repeat + 1, "strategy": strategy, "sessionId": str(sid),
                                 "agentSessionId": str(uuid.uuid4()), "prepared": False})
        state = {"blueprint": blueprint, "blueprintSha256": digest, "replicas": replicas,
                 "scope": "Frozen initial plans; subsequent turns require real production API/model execution"}

    def save():
        state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    save()
    for replica in state["replicas"]:
        (root / (replica["sessionId"] + "-initial-plan.sql")).write_text(seed_sql(replica, blueprint), encoding="utf-8")
    if not args.apply:
        print("Prepared nine initial-plan fixtures; no database or provider calls executed"); return
    inspected = json.loads(subprocess.check_output(["docker", "inspect", "study-agent-eval-app-1"]))[0]
    env = dict(v.split("=", 1) for v in inspected["Config"]["Env"])
    assert inspected["State"]["Running"] and env.get("STUDY_AGENT_ELASTICSEARCH_PHYSICAL_INDEX") == args.expected_index

    def sql(statement):
        r = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=root", "study-agent-mysql", "mysql",
                            "--default-character-set=utf8mb4", "--raw", "-uroot", "-D", "study_agent_eval", "-N"],
                           input=statement.encode("utf-8"), capture_output=True, check=True)
        return r.stdout.decode("utf-8").strip()

    def api(method, path, **kwargs):
        r = requests.request(method, "http://127.0.0.1:8080" + path, headers={"X-User-Id": "1"}, timeout=30, **kwargs)
        body = r.json()
        (root / (str(time.time_ns()) + "-api.json")).write_text(json.dumps({"path": path, "httpStatus": r.status_code, "body": body}, ensure_ascii=False), encoding="utf-8")
        r.raise_for_status(); assert body["code"] == 0, body
        return body["data"]

    for replica in state["replicas"]:
        sid = int(replica["sessionId"])
        if sql(f"SELECT COUNT(*) FROM learning_sessions WHERE id={sid};") == "0":
            sql(seed_sql(replica, blueprint))
        view = api("GET", f"/api/learning/sessions/{sid}")
        assert str(view["knowledgeBaseId"]) == str(blueprint["knowledgeBaseId"])
        assert view["learningGoal"] == blueprint["learningGoal"] and len(view["plan"]) == 5
        for i, (point, task) in enumerate(zip(view["plan"], tasks)):
            assert str(point["id"]) == str(sid + i + 1)
            for field in ["topic", "subtopics", "estimatedMinutes", "chapterTitle", "priority", "sourceChunkIds"]:
                assert point[field] == task[field], "Replica differs from frozen teaching plan"
        if not replica["prepared"]:
            assert not api("GET", f"/api/learning/sessions/{sid}/messages"), "Never reinitialize a started session"
            configured = api("POST", f"/api/eval/learning/sessions/{sid}/compression", json={"strategy": replica["strategy"]})
            assert configured["compressionStrategy"] == replica["strategy"]
            replica["prepared"] = True; save()
        assert sql(f"SELECT compression_strategy FROM learning_contexts WHERE session_id={sid} AND user_id=1;") == replica["strategy"]
        print(json.dumps({"sessionId": str(sid), "strategy": replica["strategy"], "prepared": True}))


if __name__ == "__main__":
    main()
