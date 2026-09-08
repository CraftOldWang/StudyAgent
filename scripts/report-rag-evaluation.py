"""Reproduce the final component report from immutable raw API responses, judgments and usage events."""
import argparse
import hashlib
import json
import math
import random
import statistics
from pathlib import Path

from eval_jsonl import read_jsonl


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def course_metrics(rows):
    return {"n": len(rows), "hitsAt5": sum(r["score"]["hitAt5"] for r in rows),
            "hitAt5": statistics.mean(r["score"]["hitAt5"] for r in rows),
            "mrrAt10": statistics.mean(r["score"]["reciprocalRankAt10"] for r in rows),
            "contextEvidenceHits": sum(r["score"]["contextEvidenceHit"] for r in rows),
            "meanContextTokens": statistics.mean(r["score"]["contextTokens"] for r in rows)}


def paired_bootstrap(deltas, seed=20260909, repetitions=10000):
    randomizer = random.Random(seed)
    samples = []
    for _ in range(repetitions):
        samples.append(statistics.mean(statistics.mean(randomizer.choices(group, k=len(group))) for group in deltas))
    samples.sort()
    return {"estimate": statistics.mean(statistics.mean(group) for group in deltas),
            "interval95": [samples[math.floor(.025 * repetitions)], samples[math.ceil(.975 * repetitions) - 1]],
            "method": "Percentile paired bootstrap resampling questions within each course; conditional on fixed corpus and labels",
            "seed": seed, "repetitions": repetitions}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--independent", type=Path, default=Path(".eval/runs/validation-selected-v1"))
    parser.add_argument("--paired", type=Path, default=Path(".eval/runs/validation-parent-paired-v1"))
    parser.add_argument("--answers", type=Path, default=Path(".eval/runs/validation-answers-v1"))
    parser.add_argument("--output", type=Path, default=Path("docs/evidence/m4/rag-validation-v1.json"))
    args = parser.parse_args()
    raw = read_jsonl(args.independent / "responses.jsonl")
    pairs = read_jsonl(args.paired / "pairs.jsonl")
    views = read_jsonl(args.paired / "responses.jsonl")
    judgments = json.loads((args.answers / "judgments.json").read_text(encoding="utf-8"))
    assert len(raw) == 320 and len(pairs) == 80 and len(views) == len(judgments) == 160
    assert all(r["status"] == 200 and r["response"]["code"] == 0 for r in raw + pairs)
    assert len({(r["questionId"], r["mode"]) for r in judgments}) == 160
    courses = ["algorithms", "os", "compiler"]
    modes = {}
    for mode in ["BM25", "VECTOR", "RRF"]:
        positive = [r for r in raw if r["mode"] == mode and "score" in r]
        modes[mode] = {"overall": course_metrics(positive), "byCourse": {c: course_metrics([r for r in positive if r["course"] == c]) for c in courses},
                       "medianApiMillis": statistics.median(r["elapsedMillis"] for r in positive),
                       "latencyNote": "Sequential single-run API timings including provider/network; not a controlled speed benchmark"}
    context = {}
    quality = {}
    for mode in ["RRF", "PARENT"]:
        positive = [r for r in views if r["mode"] == mode and "score" in r]
        context[mode] = {"overall": course_metrics(positive), "byCourse": {c: course_metrics([r for r in positive if r["course"] == c]) for c in courses}}
        judged = [r for r in judgments if r["mode"] == mode]
        quality[mode] = {}
        for course in ["overall"] + courses:
            subset = [r for r in judged if course == "overall" or r["course"] == course]
            pos = [r for r in subset if r["expectedAnswerable"]]
            neg = [r for r in subset if not r["expectedAnswerable"]]
            quality[mode][course] = {"answerableCount": len(pos), "acceptedAnswers": sum(r["accepted"] for r in pos),
                "acceptedAnswerRate": statistics.mean(r["accepted"] for r in pos),
                "correctAnswers": sum(r["judge"]["correct"] for r in pos),
                "groundedAnswers": sum(r["judge"]["grounded"] for r in pos),
                "insufficientCount": len(neg),
                "correctRefusals": sum(r["accepted"] and not r["answer"]["answerable"] for r in neg),
                "unsupportedResponses": sum(not r["judge"]["grounded"] for r in neg),
                "invalidCitationIds": sum(not r["citationsValid"] for r in subset)}
    raw_by_key = {(r["questionId"], r["mode"]): r for r in raw}
    view_by_key = {(r["questionId"], r["mode"]): r for r in views}
    bootstrap = {}
    for baseline in ["BM25", "VECTOR"]:
        delta = [[r["score"]["hitAt5"] - raw_by_key[r["questionId"], baseline]["score"]["hitAt5"] for r in raw
                  if r["mode"] == "RRF" and r["course"] == c and "score" in r] for c in courses]
        bootstrap["rrfHitAt5Minus" + baseline] = paired_bootstrap(delta)
    delta = [[r["score"]["contextEvidenceHit"] - view_by_key[r["questionId"], "RRF"]["score"]["contextEvidenceHit"] for r in views
              if r["mode"] == "PARENT" and r["course"] == c and "score" in r] for c in courses]
    bootstrap["parentMinusChildContextEvidenceHit"] = paired_bootstrap(delta)
    ledger = {}
    for event in read_jsonl(".eval/model-calls.jsonl"):
        ledger.setdefault(event["callId"], {})["start" if event["status"] == "STARTED" else "end"] = event
    usage = {}
    for stage in ["answer", "judge"]:
        traces = {r[stage + "Trace"] for r in judgments}
        calls = [v for v in ledger.values() if v["start"].get("traceId") in traces]
        assert len(calls) == len(traces) == 160
        known = [v["end"] for v in calls if "end" in v and v["end"].get("usageAvailable")]
        usage[stage] = {"attempts": len(calls), "usageKnown": len(known),
                        "knownInputTokens": sum(int(e["inputTokens"]) for e in known), "knownOutputTokens": sum(int(e["outputTokens"]) for e in known),
                        "knownTotalTokens": sum(int(e["totalTokens"]) for e in known)}
    mismatched = [qid for qid in {r["questionId"] for r in raw} if
                  [h["chunkId"] for h in raw_by_key[qid, "RRF"]["response"]["data"]["rankedChildren"]] !=
                  [h["chunkId"] for h in raw_by_key[qid, "PARENT"]["response"]["data"]["rankedChildren"]]]
    files = [args.independent / "manifest.json", args.independent / "responses.jsonl", args.paired / "manifest.json",
             args.paired / "pairs.jsonl", args.answers / "manifest.json", args.answers / "judgments.json", Path("eval/rag/gold-v1.jsonl")]
    audit_path = Path("docs/evidence/m4/answer-review-v1.json")
    audit = json.loads(audit_path.read_text(encoding="utf-8")) if audit_path.exists() else None
    result = {"status": "COMPONENT_VALIDATION_COMPLETE", "selectionFrozenBeforeValidation": True,
              "answerableQuestions": 60, "insufficientQuestions": 20, "retrieval": modes, "pairedContext": context,
              "modelJudgedAnswerQuality": quality, "pairedBootstrap": bootstrap, "modelUsage": usage,
              "independentRequestRankMismatchIds": sorted(mismatched),
              "artifacts": {str(p): sha(p) for p in files}, "targetedAssistantAudit": audit,
              "answerAccuracyClaimApproved": False,
              "limitations": ["Assistant-generated and reviewed gold, with incomplete semantic relevance labels",
                  "Mostly Chinese questions against mixed Chinese/English course content; BM25 baseline affected by cross-language retrieval",
                  "Same-family model judge; not human accuracy", "One fixed small validation set; no universal best-parameter claim",
                  "Controlled RAG component API evaluation, not the complete learning Agent flow",
                  "Full annotated evidence must fit in one chunk; parent comparison uses shared ranks and equal maximum body budget, not equal realized token count"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "retrieval": {k: v["overall"] for k, v in modes.items()},
                      "quality": {k: v["overall"] for k, v in quality.items()}}))


if __name__ == "__main__":
    main()
