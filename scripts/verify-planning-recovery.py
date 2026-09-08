"""Stop the eval app during one real planning stage; preserve evidence for explicit restart/resume."""
import argparse
import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--stop-at", required=True)
    args = parser.parse_args()
    state = json.loads((args.run_dir / "state.json").read_text(encoding="utf-8"))
    url = "http://localhost:8080/api/learning/plans/" + state["runId"]
    headers = {"X-User-Id": "1"}
    marker = args.run_dir / "interruption.json"
    invocation = str(time.time_ns())
    if marker.exists():
        raise RuntimeError("Interruption already recorded; inspect and resume the original run")

    def execute():
        record = {"startedAt": time.time()}
        try:
            response = requests.post(url + "/execute", headers=headers, timeout=(10, 900))
            record.update(status=response.status_code, response=response.json())
        except requests.RequestException as error:
            record["transportError"] = type(error).__name__
        finally:
            (args.run_dir / ("interrupted-request-" + invocation + ".json")).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")

    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(execute)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            response = requests.get(url, headers=headers, timeout=10)
            response.raise_for_status()
            view = response.json()["data"]
            with (args.run_dir / "interruption-poll.jsonl").open("a", encoding="utf-8") as out:
                out.write(json.dumps({"at": time.time(), "view": view}, ensure_ascii=False) + "\n")
            active = [s for s in view["stages"] if s["stage"] == args.stop_at and s["status"] == "RUNNING"]
            if active:
                result = {"runId": state["runId"], "stage": args.stop_at, "beforeStop": view, "at": time.time()}
                marker.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
                subprocess.run(["docker", "kill", "study-agent-eval-app-1"], check=True, capture_output=True)
                result["containerKilled"] = True
                marker.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
                future.result(timeout=20)
                print(json.dumps({"runId": state["runId"], "interruptedStage": args.stop_at}))
                return
            if view["status"] in {"FAILED", "SUCCEEDED"}:
                raise RuntimeError("Planning terminated before requested interruption; inspect real result")
            time.sleep(0.1)
    raise RuntimeError("No interruption was injected within the observation window")


if __name__ == "__main__":
    main()
