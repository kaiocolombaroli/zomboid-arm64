"""Gera dw_cxx_table.cpp (referencias de LINK a simbolos da libc++/libc++abi estaticas) a partir de
native/imports/classes.tsv, e cxx_selfdefined.txt (simbolos _ZGV*/_ZZ* que a propria dylib define).

Erro de nome vira erro de link: cada alvo e declarado com asm("simbolo") e seu endereco entra na
tabela; se o simbolo nao existir na libc++, o link da pzdarwin.dll falha.

Linhas 'own' que usam o simbolo real do estatico (mesmo nome Apple): typeinfos _ZTI*, guards,
__cxa_pure_virtual e ostream::sentry (objeto Apple e libc++ tem 16 bytes). As demais 'own'
(string, random_device, ...) vem de dw_cxx.cpp / dw_runtime.c / dw_libc.c.
"""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

LIB_DYLIB = {"lighting": "libLighting", "popman": "libPZPopMan", "pathfind": "libPZPathFind",
             "bullet": "libPZBullet", "clipper": "libPZClipper", "jassimp": "libjassimp"}

STATIC_REAL = re.compile(
    r"^(_ZTI.*|__cxa_guard_acquire|__cxa_guard_release|__cxa_guard_abort|__cxa_pure_virtual|"
    r"_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentry(C1ERS3_|D1Ev))$")


def read_tsv(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            line = line.rstrip("\n")
            if line:
                name, cls, alvo = (line.split("\t") + ["", ""])[:3]
                rows.append((name, cls, alvo))
    return rows


def dylib_exports(dylib_dir, lib):
    from macho_inventory import parse_macho
    with open(os.path.join(dylib_dir, LIB_DYLIB[lib] + ".dylib"), "rb") as f:
        return set(parse_macho(f.read())["exports"])


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--tsv", default=os.path.join(ROOT, "native", "imports", "classes.tsv"))
    ap.add_argument("--imports", default=os.path.join(ROOT, "native", "imports"))
    ap.add_argument("--dylibs", default=os.path.join(ROOT, "game-libs", "macos-42.21"))
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)

    importers = {}
    for lib in LIB_DYLIB:
        with open(os.path.join(a.imports, lib + ".txt"), encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    importers.setdefault(line.strip(), []).append(lib)

    exports = {}
    refs, selfdef = [], []
    for name, cls, alvo in read_tsv(a.tsv):
        if re.match(r"^_(ZGV|ZZ)", name):
            for lib in importers.get(name, []):
                if lib not in exports:
                    exports[lib] = dylib_exports(a.dylibs, lib)
                ex = exports[lib]
                if name not in ex:
                    raise SystemExit("%s: %s nao e exportado por %s" % (name, name, lib))
            selfdef.append(name)
        elif cls == "libcxx":
            if not alvo:
                raise SystemExit("%s: libcxx sem alvo" % name)
            refs.append((name, alvo))
        elif STATIC_REAL.match(name):
            refs.append((name, name))
    os.makedirs(a.out, exist_ok=True)
    lines = ["// GERADO por tools/gen_cxx_table.py a partir de classes.tsv. Nao editar.",
             '#include "dw_internal.h"', 'extern "C" {']
    for i, (_, sym) in enumerate(refs):
        lines.append('extern char dwx_%d[] asm("%s");' % (i, sym))
    lines.append("const dw_entry dw_cxx_entries[] = {")
    for i, (name, _) in enumerate(refs):
        lines.append('  {"%s", (void*)dwx_%d},' % (name, i))
    lines += ["  {nullptr, nullptr}", "};", "}", ""]
    with open(os.path.join(a.out, "dw_cxx_table.cpp"), "w", newline="\n") as f:
        f.write("\n".join(lines))
    with open(os.path.join(a.out, "cxx_selfdefined.txt"), "w", newline="\n") as f:
        f.write("\n".join(sorted(selfdef)) + "\n")
    print("dw_cxx_table.cpp: %d referencias; %d autodefinidos" % (len(refs), len(selfdef)))


if __name__ == "__main__":
    main()
