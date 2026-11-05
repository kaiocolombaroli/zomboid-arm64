import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from fps_report import stats  # noqa: E402


def rows(values, start_s=1, ram=1000):
    return [((start_s + i) * 1000, v, ram - i) for i, v in enumerate(values)]


class FpsReportTests(unittest.TestCase):
    def test_fps_mean(self):
        s = stats(rows(range(1, 101)), warmup_s=0)   # 1..100 FPS
        self.assertAlmostEqual(s["mean"], 50.5)
        self.assertAlmostEqual(s["median"], 50.5)
        self.assertAlmostEqual(s["low1"], 1.0)      # 1% de 100 amostras = a pior
        self.assertEqual(s["ram_min"], 1000 - 99)
        self.assertIsNone(s["warning"])

    def test_drops_warmup(self):
        s = stats(rows([5] * 30 + [100] * 10))        # t=1..40 s; os 30 primeiros nao contam
        self.assertEqual(s["n"], 10)
        self.assertAlmostEqual(s["mean"], 100.0)

    def test_vsync_warning(self):
        self.assertIn("vsync", stats(rows([59] * 40))["warning"])
        self.assertIn("vsync", stats(rows([30] * 40))["warning"])
        self.assertIsNone(stats(rows([45] * 40))["warning"])


if __name__ == "__main__":
    unittest.main()
