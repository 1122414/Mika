import json
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
from runpy import run_path

summarize = run_path(str(Path(__file__).resolve().parents[2] / "eval/usage_compare.py"))["summarize"]


class CompareTest(unittest.TestCase):
    def test_denominator_includes_failed_attempt_and_counts_cache_once(self):
        with tempfile.TemporaryDirectory() as temp:
            paths = [Path(temp) / name for name in ("a", "b")]
            for p, outcome in zip(paths, ("succeeded", "failed")):
                rows = [dict(type="model_reply", inputTokens=100, outputTokens=5),
                        dict(type="task_ended", outcome=outcome),
                        dict(type="model_calls", calls=2, httpCalls=2, unreported=0, httpUnreported=0,
                             inputTokens=110, cachedTokens=100, outputTokens=10)]
                p.write_text("\n".join(map(json.dumps, rows)))
            summary = summarize(paths, {paths[0].resolve()}, list(map(Decimal, ("2", "0.5", "4"))))
            self.assertEqual(240, summary["total_tokens_per_verified_success"])
            self.assertEqual(120, summary["total_tokens_per_task"])
            self.assertEqual(Decimal("0.00022"), Decimal(summary["cost_per_verified_success"]))

    def test_incomplete_export_is_not_free(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "unfinished"
            p.write_text('{"type":"model_reply","inputTokens":42}')
            result = summarize([p], set(), [Decimal(1)] * 3)
            self.assertFalse(result["usage_complete"])
            self.assertIsNone(result["estimated_cost"])

    def test_missing_model_tariff_does_not_look_like_zero_cost(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "mixed"
            summary = dict(type="model_calls", calls=2, httpCalls=2, unreported=0, httpUnreported=0,
                           inputTokens=100, cachedTokens=50, outputTokens=0, groups=[
                               dict(provider="a", model="m", purpose="main", inputTokens=70, cachedTokens=50),
                               dict(provider="b", model="m", purpose="light", inputTokens=30)])
            p.write_text(json.dumps(summary) + '\n{"type":"task_ended","outcome":"succeeded"}')
            rates = {"a": {"m": [Decimal(2), Decimal(1), Decimal(5)]}}
            self.assertIsNone(summarize([p], set(), tariffs=rates)["estimated_cost"])
            rates["b"] = {"m": [Decimal(10), Decimal(2), Decimal(8)]}
            result = summarize([p], set(), tariffs=rates)
            self.assertEqual(Decimal("0.000390"), Decimal(result["estimated_cost"]))
            self.assertIsNone(result["cost_per_verified_success"])
