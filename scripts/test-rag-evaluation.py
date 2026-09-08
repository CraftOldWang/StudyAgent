"""Metric tests use known relevance outcomes, independent of production chunking."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("scorer", Path(__file__).with_name("run-rag-evaluation.py"))
scorer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scorer)


class EvidenceScoreTest(unittest.TestCase):
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
