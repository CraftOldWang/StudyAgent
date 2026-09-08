"""Summarize the production model ledger; unknown usage never becomes zero usage."""
import argparse
import json
from collections import Counter
from pathlib import Path


def summarize(path):
    calls = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        event = json.loads(line)
        call = calls.setdefault(event["callId"], {})
        if event["status"] == "STARTED":
            if "start" in call:
                raise ValueError("duplicate call start")
            call["start"] = event
        else:
            if "end" in call:
                raise ValueError("duplicate call terminal event")
            call["end"] = event
    if any("start" not in call for call in calls.values()):
        raise ValueError("terminal event without start")
    ends = [call["end"] for call in calls.values() if "end" in call]
    known = [event for event in ends if event["usageAvailable"]]
    return {
        "attempts": len(calls),
        "statusCounts": dict(Counter(event["status"] for event in ends)),
        "withoutTerminalEvent": len(calls) - len(ends),
        "attemptsWithoutUsage": len(calls) - len(known),
        "usageComplete": len(calls) == len(known),
        "knownInputTokens": sum(int(event["inputTokens"]) for event in known),
        "knownOutputTokens": sum(int(event["outputTokens"]) for event in known),
        "knownCachedInputTokens": sum(int(event["cachedInputTokens"]) for event in known),
        "knownTotalTokens": sum(int(event["totalTokens"]) for event in known),
        "cacheNote": "Cached input is a subset of input; SDK cache zero may mean unreported.",
        "totalsNote": "Known totals exclude missing usage and are lower bounds when usageComplete=false.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("ledger", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.ledger), ensure_ascii=False, indent=2))
