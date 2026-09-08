"""Summarize durable SDK-call usage; unavailable provider usage stays unknown."""
import argparse
import collections
import json
from pathlib import Path


def summarize(path):
    calls = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        event = json.loads(line)
        calls.setdefault(event["callId"], {}).update(event)
    groups = {}
    for call in calls.values():
        key = (call.get("operation"), call.get("purpose"), call.get("model"), call.get("dimensions"))
        group = groups.setdefault(key, {"operation": key[0], "purpose": key[1], "model": key[2],
                                      "dimensions": key[3], "calls": 0, "statuses": collections.Counter(),
                                      "knownTotalTokens": 0, "unknownUsageCalls": 0})
        group["calls"] += 1
        group["statuses"][call.get("status", "STARTED")] += 1
        if call.get("totalTokens") is None:
            group["unknownUsageCalls"] += 1
        else:
            group["knownTotalTokens"] += int(call["totalTokens"])
    return {"boundary": "DASHSCOPE_SDK_CALL", "totalCalls": len(calls), "groups": list(groups.values())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("ledger", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = json.dumps(summarize(args.ledger), ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
