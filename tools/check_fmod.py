"""Confere as DLLs FMOD (core + studio): arquitetura, versao e se exportam todas as funcoes C `FMOD_*`
que libfmodintegration.dylib importa.  Uso: python -I tools/check_fmod.py [--core DLL] [--studio DLL] [--game-core DLL]"""
import argparse
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from pe_exports import pe_exports  # noqa: E402

GAME = os.environ.get("PZ_GAME_DIR", r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")
_MACHINES = {0xAA64: "arm64", 0x8664: "x64", 0x14C: "x86"}


def pe_arch(path):
    with open(path, "rb") as f:
        d = f.read(0x400)
    pe = struct.unpack_from("<I", d, 0x3C)[0]
    if d[pe:pe + 4] != b"PE\0\0":
        raise ValueError("nao e PE: " + path)
    return _MACHINES.get(struct.unpack_from("<H", d, pe + 4)[0], "outra")


def pe_version(path):
    """'2.3.9 (build 155273)' do recurso FileVersion (UTF-16); None se nao achar."""
    with open(path, "rb") as f:
        d = f.read()
    i = d.find("FileVersion".encode("utf-16-le"))
    if i < 0:
        return None
    s = d[i + 22:i + 22 + 120].decode("utf-16-le", "ignore")
    m = re.search(r"\d+\.\d+\.\d+(?:\s*\(build \d+\))?", s)
    return m.group(0) if m else None


def check_fmod(core, studio, imports):
    """arch ('arm64'/'x64'/... ou 'mista'), version (core e studio iguais, senao 'a / b') e missing (nomes de
    `imports` que nenhuma das duas DLLs exporta)."""
    a, b = pe_arch(core), pe_arch(studio)
    va, vb = pe_version(core), pe_version(studio)
    have = set(pe_exports(core)) | set(pe_exports(studio))
    return {"arch": a if a == b else "mista", "version": va if va == vb else "%s / %s" % (va, vb),
            "missing": sorted(n for n in imports if n not in have)}


def dylib_c_imports(dylib_path):
    from macho_inventory import parse_macho
    with open(dylib_path, "rb") as f:
        info = parse_macho(f.read())
    return sorted(n for n in info["imports"] if n.startswith("FMOD_"))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--core", default=os.path.join(ROOT, "third-party", "fmod", "fmod.dll"))
    ap.add_argument("--studio", default=os.path.join(ROOT, "third-party", "fmod", "fmodstudio.dll"))
    ap.add_argument("--dylib", default=os.path.join(ROOT, "game-libs", "macos-42.21", "libfmodintegration.dylib"))
    ap.add_argument("--game-core", default=os.path.join(GAME, "fmod.dll"))
    a = ap.parse_args(argv)
    imps = dylib_c_imports(a.dylib)
    r = check_fmod(a.core, a.studio, imps)
    print("simbolos FMOD_* importados pela dylib: %d" % len(imps))
    print("arquitetura: %s   versao: %s" % (r["arch"], r["version"]))
    if os.path.exists(a.game_core):
        print("jogo (x64): %s   versao: %s" % (pe_arch(a.game_core), pe_version(a.game_core)))
    if r["missing"]:
        print("AUSENTES (%d): %s" % (len(r["missing"]), " ".join(r["missing"])))
    ok = r["arch"] == "arm64" and not r["missing"]
    print("OK" if ok else "FALHA")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
