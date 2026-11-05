"""Relatorio de FPS a partir de logs/fps-<arch>-<timestamp>.csv (t_ms,frames,ram_livre_mb).
Uso: python tools/fps_report.py [csv ...]   (sem argumentos: logs/fps-*.csv)
"""
import csv
import glob
import math
import os
import re
import statistics
import sys

WARMUP_S = 30
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return [(int(r["t_ms"]), int(r["frames"]), int(r["ram_livre_mb"])) for r in csv.DictReader(f)]


def stats(rows, warmup_s=WARMUP_S):
    """rows: (t_ms, frames no intervalo, ram_livre_mb). Descarta t_ms <= warmup_s*1000."""
    fps, ram, prev = [], [], 0
    for t, fr, r in rows:
        dt = t - prev
        prev = t
        if t <= warmup_s * 1000 or dt <= 0:
            continue
        fps.append(fr * 1000.0 / dt)
        if r >= 0:
            ram.append(r)
    if not fps:
        return None
    low = sorted(fps)[:max(1, math.ceil(len(fps) / 100))]
    mean = statistics.fmean(fps)
    warn = [v for v in (60, 30) if abs(mean - v) <= 0.02 * v]
    return {"n": len(fps), "mean": mean, "median": statistics.median(fps), "low1": statistics.fmean(low),
            "ram_min": min(ram) if ram else None,
            "warning": ("media perto de %d FPS: possivel vsync ligado, resultado suspeito" % warn[0]) if warn else None}


def arch_of(path):
    m = re.match(r"fps-([A-Za-z0-9_]+)-", os.path.basename(path))
    return m.group(1) if m else "?"


def main(argv):
    files = argv or sorted(glob.glob(os.path.join(ROOT, "logs", "fps-*.csv")))
    if not files:
        print("nenhum CSV encontrado")
        return 1
    print("%-34s %6s %8s %8s %8s %8s" % ("arquivo", "n", "media", "mediana", "1%baixo", "RAM min"))
    by_arch = {}
    for p in files:
        s = stats(load_csv(p))
        if not s:
            print("%-34s sem dados apos %ds de aquecimento" % (os.path.basename(p), WARMUP_S))
            continue
        by_arch.setdefault(arch_of(p), []).append(s)
        print("%-34s %6d %8.1f %8.1f %8.1f %8s" % (os.path.basename(p), s["n"], s["mean"], s["median"], s["low1"],
                                                   "-" if s["ram_min"] is None else "%d MB" % s["ram_min"]))
        if s["warning"]:
            print("  AVISO: " + s["warning"])
    means = {a: statistics.fmean(s["mean"] for s in v) for a, v in by_arch.items()}
    for a, m in means.items():
        print("media %s: %.1f FPS (%d execucoes)" % (a, m, len(by_arch[a])))
    if "aarch64" in means and "amd64" in means and means["amd64"] > 0:
        print("razao aarch64/amd64: %.2fx" % (means["aarch64"] / means["amd64"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
