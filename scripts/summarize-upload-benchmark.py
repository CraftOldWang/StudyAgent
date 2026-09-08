"""Join raw client timings with server phase logs; failed attempts remain in the report."""
import argparse
import json
import re
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dirs", type=Path, nargs="+")
    parser.add_argument("--server-log", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    phases = {}
    for path in args.server_log:
        for phase, session, size, millis in re.findall(
                r"UPLOAD_METRIC phase=(\w+) uploadSessionId=(\d+) bytes=(\d+) elapsedMillis=(\d+)",
                path.read_text(encoding="utf-8", errors="replace")):
            phases.setdefault(session, {})[phase.lower() + "Seconds"] = int(millis) / 1000
    records = []
    manifests = []
    for directory in args.run_dirs:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        manifests.append(manifest)
        for path in sorted(directory.glob(manifest["arm"] + "-c*-t*.json")):
            record = json.loads(path.read_text(encoding="utf-8"))
            record["source"] = str(path)
            record["serverPhases"] = phases.get(str(record.get("init", {}).get("uploadSessionId")), {})
            records.append(record)
    groups = []
    for arm, concurrency in sorted({(r["arm"], r["concurrency"]) for r in records}):
        trials = [r for r in records if r["arm"] == arm and r["concurrency"] == concurrency]
        successful = [r for r in trials if r["status"] == "SUCCEEDED"]
        group = {"arm": arm, "concurrency": concurrency, "attempts": len(trials),
                 "succeeded": len(successful), "failed": len(trials) - len(successful), "timings": {}}
        fields = ("totalSeconds", "hashSeconds", "initSeconds", "uploadSeconds", "statusSeconds", "completeSeconds")
        for field in fields:
            values = [r[field] for r in successful if field in r]
            if values:
                group["timings"][field] = {"median": statistics.median(values), "min": min(values),
                                          "max": max(values), "mean": statistics.mean(values), "n": len(values)}
        for phase in sorted({key for r in successful for key in r["serverPhases"]}):
            values = [r["serverPhases"][phase] for r in successful if phase in r["serverPhases"]]
            group["timings"]["server_" + phase] = {"median": statistics.median(values), "min": min(values),
                                                   "max": max(values), "mean": statistics.mean(values), "n": len(values)}
        groups.append(group)
    result = {"manifests": manifests, "groups": groups, "rawTrials": records,
              "limits": ["Local Windows client to Docker Desktop API; no WAN latency or packet loss simulated",
                         "One fixed deterministic byte payload; excludes parsing/indexing",
                         "Five trials per arm measure this environment, not population tail latency",
                         "Old/new arms run in separate deployment batches; serial/concurrent order alternates within a batch"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(groups, indent=2))


if __name__ == "__main__":
    main()
