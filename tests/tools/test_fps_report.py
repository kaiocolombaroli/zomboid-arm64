import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import tempfile  # noqa: E402
from fps_report import stats, load_csv, load_csv_full  # noqa: E402


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


class FpsCsvFormatTests(unittest.TestCase):
    def write(self, text):
        fd, p = tempfile.mkstemp(suffix=".csv")
        os.close(fd)
        self.addCleanup(os.remove, p)
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(text)
        return p

    def test_old_format(self):
        p = self.write("t_ms,frames,ram_livre_mb\n1000,60,900\n2000,58,890\n")
        self.assertEqual(load_csv(p), [(1000, 60, 900), (2000, 58, 890)])
        self.assertEqual(load_csv_full(p)[0], (1000, 60, 900, None, None, None))
        s = stats(load_csv_full(p), warmup_s=0)
        self.assertEqual(s["n"], 2)
        self.assertIsNone(s["max_ms"])

    def test_new_format_with_comment(self):
        p = self.write("# t=12:00:00.000 framebuffer=2880x1920 janela=2880x1920\nt_ms,frames,ram_livre_mb,max_ms,over33,over50\n"
                       "1000,30,900,80,4,2\n# t=x framebuffer=1920x1280 janela=1920x1280\n2000,40,890,45,1,0\n")
        self.assertEqual(load_csv(p), [(1000, 30, 900), (2000, 40, 890)])
        self.assertEqual(load_csv_full(p), [(1000, 30, 900, 80, 4, 2), (2000, 40, 890, 45, 1, 0)])
        s = stats(load_csv_full(p), warmup_s=0)
        self.assertEqual((s["max_ms"], s["over33"], s["over50"]), (80, 5, 2))
        self.assertAlmostEqual(s["mean"], 35.0)


if __name__ == "__main__":
    unittest.main()
