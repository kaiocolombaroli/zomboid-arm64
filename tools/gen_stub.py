#!/usr/bin/env python3
"""Gera <lib>_stub.S, <lib>.def, <lib>_names.h e <lib>_methods.tsv para uma DLL stub ARM64.
Os nomes vem dos exports Java_* da DLL x64 do jogo (so leitura); o jar (so leitura) da a
classe/descritor/retorno de cada um. Export sem metodo native correspondente no jar e gerado
mesmo assim (retorno inteiro zero) e listado em <lib>_unmatched.txt e no stdout.
Uso: python -I gen_stub.py --lib ZNetJNI64 --dll <dll x64> --jar <jar> --out <dir>"""
import argparse
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_bridge import ACC_NATIVE, jni_long, jni_short, mangle, parse_class  # noqa: E402
from pe_exports import pe_exports  # noqa: E402


def native_methods(jar, classes):
    """Metodos native das classes (nomes internos 'a/b/C') do jar.
    Cada item: jni_name (curto, ou longo se o nome se repete na classe), jni_short, jni_long,
    descriptor, ret (descritor do retorno), cls, method, static."""
    z = zipfile.ZipFile(jar)
    out = []
    for c in classes:
        data = z.read(c + ".class")
        cname, meths = parse_class(data)
        nat = [(m, d, f) for m, d, f in meths if f & ACC_NATIVE]
        counts = {}
        for m, _, _ in nat:
            counts[m] = counts.get(m, 0) + 1
        for m, d, f in nat:
            s, lg = jni_short(cname, m), jni_long(cname, m, d)
            out.append({"jni_name": lg if counts[m] > 1 else s, "jni_short": s, "jni_long": lg,
                        "descriptor": d, "ret": d[d.index(")") + 1:], "cls": cname, "method": m,
                        "static": bool(f & 0x0008)})
    return out


def classes_for(jar, exports):
    z = zipfile.ZipFile(jar)
    out = []
    for nm in z.namelist():
        if nm.endswith(".class"):
            pre = "Java_" + mangle(nm[:-6]) + "_"
            if any(e.startswith(pre) for e in exports):
                out.append(nm[:-6])
    return sorted(out)


def match(exports, natives):
    """-> ([(export, native|None)], [export sem native])."""
    by_long, by_short = {}, {}
    for n in natives:
        by_long.setdefault(n["jni_long"], n)
        by_short.setdefault(n["jni_short"], n)
    pairs, missing = [], []
    for e in exports:
        n = by_long.get(e) or by_short.get(e)
        pairs.append((e, n))
        if n is None:
            missing.append(e)
    return pairs, missing


def generate(lib, dll_path, jar_path, out_dir):
    exports = [e for e in pe_exports(dll_path) if e.startswith("Java_")]
    pairs, missing = match(exports, native_methods(jar_path, classes_for(jar_path, exports)))
    os.makedirs(out_dir, exist_ok=True)
    n = len(pairs)
    s = ["// gerado por tools/gen_stub.py - nao editar", "    .text", "    .align 2"]
    for i, (name, _) in enumerate(pairs):
        s += ["    .globl %s" % name, "%s:" % name, "    mov x16, #%d" % i, "    b stub_common"]
    s += ["    .globl g_stub_seen", "    .bss", "    .align 3",
          "g_stub_seen:", "    .space %d" % (8 * ((n + 63) // 64) or 8)]
    h = ["/* gerado por tools/gen_stub.py - nao editar */", "#define STUB_COUNT %d" % n,
         "static const char* const g_stub_names[STUB_COUNT] = {"]
    h += ['    "%s",' % name for name, _ in pairs]
    h.append("};")
    d = ["LIBRARY %s.dll" % lib, "EXPORTS"] + ["    " + name for name, _ in pairs]
    tsv = []
    for name, nat in pairs:
        if nat:
            tsv.append("\t".join([name, nat["cls"].replace("/", "."), nat["method"], nat["descriptor"],
                                  "1" if nat["static"] else "0"]))
        else:
            tsv.append("\t".join([name, "", "", "", "0"]))
    files = {lib + "_stub.S": s, lib + "_names.h": h, lib + ".def": d, lib + "_methods.tsv": tsv,
             lib + "_unmatched.txt": missing}
    for fn, lines in files.items():
        with open(os.path.join(out_dir, fn), "w", newline="\n") as f:
            f.write("".join(l + "\n" for l in lines))
    return pairs, missing


def main(argv=None):
    ap = argparse.ArgumentParser()
    for a in ("lib", "dll", "jar", "out"):
        ap.add_argument("--" + a, required=True)
    a = ap.parse_args(argv)
    pairs, missing = generate(a.lib, a.dll, a.jar, a.out)
    print("gen_stub: %s: %d exports Java_*, %d sem metodo native no jar" % (a.lib, len(pairs), len(missing)))
    for m in missing:
        print("gen_stub:   sem native: " + m)
    return 0


if __name__ == "__main__":
    sys.exit(main())
