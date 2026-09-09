"""Synthetic accounting checks only; these fixtures are never experiment evidence."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ReportTest(unittest.TestCase):
    def test_failed_or_unknown_usage_blocks_comparison_and_keeps_denominator(self):
        for failure in [None, "FAILED", "UNKNOWN_USAGE"]:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(prefix="report-unit-", dir=".eval") as directory:
                root = Path(directory); schedule = []; events = []
                for course in ["algorithms", "os"]:
                    for strategy, cost in [("THRESHOLD", 100), ("WHOLE_HISTORY", 90), ("LOCAL", 80)]:
                        for repeat in [1, 2, 3]:
                            sid = str(len(schedule) + 1)
                            affected = course == "algorithms" and strategy == "LOCAL" and repeat == 1
                            schedule.append({"sessionId": sid, "course": course, "strategy": strategy, "repeat": repeat,
                                             "status": "FAILED" if affected and failure == "FAILED" else "PASSED"})
                            run = root / sid; run.mkdir()
                            (run / "state.json").write_text(json.dumps({"steps": {str(i): {"verified": True} for i in range(23)}}), encoding="utf-8")
                            # Two attempts include the summary. Their sum is cost, not just the learning call.
                            for kind, amount in [("LEARNING", cost - 10), ("COMPACTION", 10)]:
                                operation = f"LEARNING/{sid}/{sid}/0" if kind == "LEARNING" else f"COMPACTION/{sid}/POINT"
                                call_id = sid + kind
                                events.append({"callId": call_id, "status": "STARTED", "operation": operation})
                                events.append({"callId": call_id, "status": "SUCCEEDED", "usageAvailable": not (affected and failure == "UNKNOWN_USAGE"),
                                               "inputTokens": amount, "outputTokens": 5, "totalTokens": amount + 5, "cachedInputTokens": 0})
                (root / "experiment.json").write_text(json.dumps({"schedule": schedule}), encoding="utf-8")
                ledger = root / "ledger.jsonl"
                ledger.write_text("\n".join(json.dumps(e) for e in events), encoding="utf-8")
                subprocess.run([sys.executable, "scripts/report-compaction-experiment.py", "--run-dir", str(root), "--ledger", str(ledger)], check=True, capture_output=True)
                report = json.loads((root / "report.json").read_text(encoding="utf-8"))
                self.assertEqual(report["scheduled"], 18)
                self.assertEqual(len(report["sessions"]), 18)
                group = next(g for g in report["groups"] if g["course"] == "algorithms" and g["strategy"] == "LOCAL")
                self.assertEqual(group["scheduled"], 3)
                reduction = report["comparisons"][0]["relativeReductionOfMedians"]["knownInputTokens"]
                if failure:
                    self.assertIsNone(reduction)
                    self.assertIsNone(group["metrics"]["knownInputTokens"]["median"])
                else:
                    self.assertAlmostEqual(reduction, 0.2)
                    self.assertEqual(group["metrics"]["knownInputTokens"]["median"], 80)
                self.assertAlmostEqual(report["comparisons"][1]["relativeReductionOfMedians"]["knownInputTokens"], 0.2)


if __name__ == "__main__":
    unittest.main()
