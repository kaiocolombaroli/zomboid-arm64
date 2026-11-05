"""Gera native/imports/fmodintegration.txt: importacoes de libfmodintegration.dylib que dw_resolve deve atender
(libSystem/libc++). Ficam de fora: a API C `FMOD_*` e a API C++ `FMOD::*` (libfmod/libfmodstudio, tratadas na
ponte FMOD) e os simbolos que a propria dylib exporta (webrtc, EventInstanceCallback, rtc, guards)."""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))


def fmod_dylib_imports(data):
    from macho_inventory import parse_macho
    info = parse_macho(data)
    own = set(info["exports"])
    out = set()
    for n in info["imports"]:
        if n.startswith("FMOD_") or "4FMOD" in n or n in own:
            continue
        out.add(n)
    return sorted(out)


if __name__ == "__main__":
    src = os.path.join(ROOT, "game-libs", "macos-42.21", "libfmodintegration.dylib")
    dst = os.path.join(ROOT, "native", "imports", "fmodintegration.txt")
    with open(src, "rb") as f:
        names = fmod_dylib_imports(f.read())
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(names) + "\n")
    print(dst, len(names))
