"""Aggregate all scheduled sessions; emit recall answers separately for semantic review."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
from statistics import median
import sys

spec = importlib.util.spec_from_file_location("learning_usage", Path(__file__).with_name("report-learning-usage.py"))
usage_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(usage_module)
summarize = usage_module.summarize


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--ledger", type=Path, default=Path(".eval/model-calls.jsonl"))
    args = parser.parse_args()
    root = args.run_dir
    experiment = read(root / "experiment.json")
    rubric = read(Path("eval/planning/compaction-quality-v1.json"))
    events = [json.loads(line) for line in args.ledger.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    rows = []; reviews = []
    for item in experiment["schedule"]:
        sid = item["sessionId"]
        usage = summarize(events, sid)
        state_path = root / sid / "state.json"
        state = read(state_path) if state_path.exists() else {}
        steps = state.get("steps", {})
        row = {k: item[k] for k in ["sessionId", "course", "repeat", "strategy", "status"]}
        row.update(verifiedTurns=sum(bool(s.get("verified")) for s in steps.values()), usage=usage["combined"],
                   summaryUsage=usage["compaction"], probes=[])
        for i in [0, 2, 4]:
            if not state.get("plan"): continue
            point = state["plan"][i]
            step = steps.get(str(point["id"]) + "/question", {})
            turn = step.get("turn", {})
            answer = turn.get("assistantMessage", "")
            row["probes"].append({"point": i + 1, "turnId": turn.get("id"), "status": turn.get("status", "NOT_RUN"),
                                  "markerLiteralPresent": rubric["courses"][item["course"]]["personalMarker"] in answer,
                                  "answerSha256": hashlib.sha256(answer.encode()).hexdigest() if answer else None,
                                  "semanticReview": "PENDING"})
            if answer:
                reviews.append({"sessionId": sid, "course": item["course"], "strategy": item["strategy"], "repeat": item["repeat"],
                                "point": i + 1, "turnId": turn["id"], "answer": answer,
                                "rubric": rubric["courses"][item["course"]], "statePath": state_path.as_posix()})
        rows.append(row)
    groups = []
    for course in ["algorithms", "os"]:
        for strategy in ["THRESHOLD", "WHOLE_HISTORY", "LOCAL"]:
            selected = [r for r in rows if r["course"] == course and r["strategy"] == strategy]
            complete = len(selected) == 3 and all(r["status"] == "PASSED" and r["verifiedTurns"] == 23
                and r["usage"]["usageComplete"] and r["usage"]["attempts"] > 0 for r in selected)
            group = {"course": course, "strategy": strategy, "scheduled": len(selected),
                     "statusCounts": dict(Counter(r["status"] for r in selected)), "completeComparableGroup": complete}
            group["metrics"] = {}
            for metric in ["knownInputTokens", "knownOutputTokens", "knownTotalTokens", "attempts"]:
                values = [r["usage"][metric] for r in selected]
                group["metrics"][metric] = {"observedValuesIncludingIncomplete": values,
                                            "median": median(values) if complete else None,
                                            "min": min(values) if complete else None, "max": max(values) if complete else None}
            groups.append(group)
    comparisons = []
    for course in ["algorithms", "os"]:
        a = next(g for g in groups if g["course"] == course and g["strategy"] == "THRESHOLD")
        c = next(g for g in groups if g["course"] == course and g["strategy"] == "LOCAL")
        values = {}
        for metric in ["knownInputTokens", "knownTotalTokens"]:
            baseline, local = a["metrics"][metric]["median"], c["metrics"][metric]["median"]
            values[metric] = (baseline - local) / baseline if baseline and local is not None else None
        comparisons.append({"course": course, "relativeReductionOfMedians": values, "qualityGate": "SEMANTIC_REVIEW_REQUIRED"})
    report = {"version": "compaction-report-v1", "experiment": (root / "experiment.json").as_posix(),
              "scheduled": len(rows), "statusCounts": dict(Counter(r["status"] for r in rows)), "sessions": rows,
              "groups": groups, "comparisons": comparisons,
              "limits": ["Every scheduled session remains in the denominator; incomplete groups get no comparison median.",
                         "Usage includes all learning, summary and retry attempts. Missing usage is not zero.",
                         "Literal marker screening is not semantic quality acceptance. Review all retained probe answers."]}
    (root / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (root / "quality-review-input.json").write_text(json.dumps(reviews, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"scheduled": len(rows), "statusCounts": report["statusCounts"], "reviewAnswers": len(reviews)}))


if __name__ == "__main__":
    main()
