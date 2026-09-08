"""Metric tests use known relevance outcomes, independent of production chunking."""
import importlib.util
import unittest
import json
import tempfile
from pathlib import Path
from eval_jsonl import read_jsonl

spec = importlib.util.spec_from_file_location("scorer", Path(__file__).with_name("run-rag-evaluation.py"))
scorer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scorer)
report_spec = importlib.util.spec_from_file_location("reporter", Path(__file__).with_name("report-rag-evaluation.py"))
reporter = importlib.util.module_from_spec(report_spec)
report_spec.loader.exec_module(reporter)


class EvidenceScoreTest(unittest.TestCase):
    def test_stratified_interval_preserves_course_macro_weighting(self):
        result = reporter.paired_bootstrap([[0], [1] * 20], repetitions=100)
        self.assertEqual(result["estimate"], 0.5)
        self.assertEqual(result["interval95"], [0.5, 0.5])

    def test_jsonl_preserves_unicode_separators_inside_source_text(self):
        rows = [{"content": "first\u2028second\u0085third\u2029fourth"}, {"content": "next"}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "responses.jsonl"
            path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
            self.assertEqual(read_jsonl(path), rows)

    def test_rank_and_scope(self):
        question = {"evidence": [{"sourceSha256": "a", "quote": "Alpha β"}]}
        hit = lambda doc, text: {"provenance": {"documentId": doc}, "content": text}
        data = {"rankedChildren": [hit("2", "Alpha β"), hit("1", "irrelevant"), hit("1", "ＡLPHA\nβ")],
                "hits": [hit("1", "context Alpha β tail")], "contextTokens": 12}
        result = scorer.score(data, question, {"1": "a", "2": "b"})
        self.assertEqual(result["firstAnnotatedEvidenceRank"], 3)
        self.assertEqual(result["reciprocalRankAt10"], 1 / 3)
        self.assertEqual(result["contextEvidenceHit"], 1)

    def test_rank_six_is_not_hit_at_five(self):
        question = {"evidence": [{"sourceSha256": "a", "quote": "fact"}]}
        hit = lambda text: {"provenance": {"documentId": "1"}, "content": text}
        result = scorer.score({"rankedChildren": [hit("other")] * 5 + [hit("fact")], "hits": [], "contextTokens": 0}, question, {"1": "a"})
        self.assertEqual(result["hitAt5"], 0)
        self.assertEqual(result["reciprocalRankAt10"], 1 / 6)
        self.assertEqual(result["contextEvidenceHit"], 0)

    def test_partial_quote_and_unknown_document_are_not_relevant(self):
        question = {"evidence": [{"sourceSha256": "a", "quote": "complete fact"}]}
        hit = {"provenance": {"documentId": "1"}, "content": "complete"}
        self.assertFalse(scorer.matches(hit, question, {"1": "a"}))
        with self.assertRaises(ValueError):
            scorer.matches(hit, question, {})


if __name__ == "__main__":
    unittest.main()
