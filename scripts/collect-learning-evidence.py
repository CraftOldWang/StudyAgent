"""Read-only export of one eval session's trace, summaries and saved model context.

Learning must have run through the production API. These SQL reads inspect its
persisted outputs; they do not inject experiment results or reconstruct usage.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-id", type=int, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    assert args.session_id > 0
    root = args.output_dir; root.mkdir(parents=True, exist_ok=True)
    scope = f"user_id=1 AND session_id={args.session_id}"

    def rows(query):
        result = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=root", "study-agent-mysql", "mysql",
                                 "--default-character-set=utf8mb4", "--raw", "-uroot", "-D", "study_agent_eval", "-N"],
                                input=query.encode("utf-8"), capture_output=True, check=True)
        return [json.loads(line) for line in result.stdout.decode("utf-8").splitlines() if line.strip()]

    traces = rows("SELECT JSON_OBJECT('traceId',trace_id,'sequenceNo',sequence_no,'stage',stage,'eventType',event_type,"
                  "'status',status,'summary',summary,'payload',payload_json,'elapsedMillis',elapsed_millis,'toolCallId',tool_call_id) "
                  f"FROM agent_trace_events WHERE {scope} ORDER BY id;")
    for event in traces:
        if isinstance(event["payload"], str):
            event["payload"] = json.loads(event["payload"])
    compactions = rows("SELECT JSON_OBJECT('turnId',CAST(turn_id AS CHAR),'knowledgePointId',CAST(knowledge_point_id AS CHAR),"
                       "'kind',kind,'inputHash',input_hash,'summary',summary_text,'traceId',trace_id) "
                       f"FROM learning_compactions WHERE {scope} ORDER BY id;")
    contexts = rows("SELECT JSON_OBJECT('strategy',compression_strategy,'state',agent_state_json) "
                    f"FROM learning_contexts WHERE {scope};")
    assert len(contexts) == 1, "Require a persisted model context"
    if isinstance(contexts[0]["state"], str):
        contexts[0]["state"] = json.loads(contexts[0]["state"])
    manifest = {"sessionId": str(args.session_id), "exportedAt": datetime.now(timezone.utc).isoformat(),
                "traceEvents": len(traces), "traceStages": dict(Counter(t["stage"] for t in traces)),
                "compactionKinds": dict(Counter(c["kind"] for c in compactions)), "files": [],
                "limits": ["State/trace export is observational evidence, not a quality or token-reduction score."]}
    for name, value in [("trace-events.json", traces), ("compactions.json", compactions), ("context.json", contexts[0])]:
        path = root / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        manifest["files"].append({"path": path.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: manifest[k] for k in ["sessionId", "traceEvents", "traceStages", "compactionKinds"]}))


if __name__ == "__main__":
    main()
