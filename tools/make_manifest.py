#!/usr/bin/env python3
"""Grava dist/manifest.json: hash/tamanho do projectzomboid.jar e de cada DLL de dist/.
Uso: python -I make_manifest.py [--dist D] [--jar J] [--version-file V] [--out O]"""
import argparse
import hashlib
import json
import os
import sys

GAME = os.environ.get("PZ_GAME_DIR", r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def game_version(path):
    """Primeira linha de version.txt (ex.: '42.21.0 4a0e9546ec'); None se indisponivel."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            line = f.readline().strip()
        return line or None
    except OSError:
        return None


def build(dist, jar, version_file):
    dlls = {}
    for n in sorted(os.listdir(dist)):
        p = os.path.join(dist, n)
        if n.lower().endswith(".dll") and os.path.isfile(p):
            dlls[n] = {"size": os.path.getsize(p), "sha256": sha256(p)}
    return {
        "jar_sha256": sha256(jar),
        "jar_size": os.path.getsize(jar),
        "game_version": game_version(version_file),
        "dlls": dlls,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dist", default=os.path.join(ROOT, "dist"))
    ap.add_argument("--jar", default=os.path.join(GAME, "projectzomboid.jar"))
    ap.add_argument("--version-file", default=os.path.join(os.path.expanduser("~"), "Zomboid", "version.txt"))
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    if not os.path.isdir(a.dist):
        sys.exit("dist nao encontrado: %s" % a.dist)
    if not os.path.isfile(a.jar):
        sys.exit("jar nao encontrado: %s" % a.jar)
    out = a.out or os.path.join(a.dist, "manifest.json")
    m = build(a.dist, a.jar, a.version_file)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(m, f, indent=2)
        f.write("\n")
    print("manifest: %s (%d DLLs, game_version=%s)" % (out, len(m["dlls"]), m["game_version"]))


if __name__ == "__main__":
    main()
