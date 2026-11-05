#!/usr/bin/env python3
"""Gera <lib>_table.h, <lib>_thunks.S e <lib>.def a partir dos exports Java_* de uma dylib e das
assinaturas native do projectzomboid.jar (lido apenas).
Uso: python -I gen_bridge.py --lib Lighting64 --dylib <dylib> --jar <jar> --out <dir>"""
import argparse
import hashlib
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from macho_inventory import parse_macho  # noqa: E402

ACC_NATIVE = 0x0100
_LETTER = {"Z": "C", "B": "c", "S": "w", "C": "W", "I": "i", "J": "I", "F": "f", "D": "d"}


class GenError(Exception):
    pass


def parse_class(data):
    """Retorna (nome_da_classe, [(metodo, descritor, flags)]). Parser minimo de class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("nao e class file")
    p = 8
    n = struct.unpack_from(">H", data, p)[0]
    p += 2
    utf, cls_idx = {}, {}
    i = 1
    while i < n:
        t = data[p]
        if t == 1:
            ln = struct.unpack_from(">H", data, p + 1)[0]
            utf[i] = data[p + 3:p + 3 + ln].decode("utf-8", "replace")
            p += 3 + ln
        elif t == 7:
            cls_idx[i] = struct.unpack_from(">H", data, p + 1)[0]
            p += 3
        elif t in (3, 4, 9, 10, 11, 12, 17, 18):
            p += 5
        elif t in (5, 6):
            p += 9
            i += 1
        elif t in (8, 16, 19, 20):
            p += 3
        elif t == 15:
            p += 4
        else:
            raise ValueError("tag %d" % t)
        i += 1
    this_idx = struct.unpack_from(">H", data, p + 2)[0]
    name = utf[cls_idx[this_idx]]
    p += 6
    ic = struct.unpack_from(">H", data, p)[0]
    p += 2 + 2 * ic

    def members():
        nonlocal p
        out = []
        c = struct.unpack_from(">H", data, p)[0]
        p += 2
        for _ in range(c):
            fl, ni, di, ac = struct.unpack_from(">HHHH", data, p)
            p += 8
            out.append((utf.get(ni, "?"), utf.get(di, "?"), fl))
            for _ in range(ac):
                ln = struct.unpack_from(">I", data, p + 2)[0]
                p += 6 + ln
        return out

    members()
    return name, members()


def _split_args(descriptor):
    if not descriptor.startswith("("):
        raise ValueError("descritor invalido: " + descriptor)
    end = descriptor.index(")")
    s, i, out = descriptor[1:end], 0, []
    while i < len(s):
        j = i
        while s[j] == "[":
            j += 1
        if s[j] == "L":
            j = s.index(";", j)
        out.append(s[i:j + 1])
        i = j + 1
    return out


def arg_letters(descriptor):
    """Letras por argumento, com 'pp' iniciais (JNIEnv* e jclass/jobject)."""
    out = "pp"
    for a in _split_args(descriptor):
        out += "p" if a[0] in "L[" else _LETTER[a[0]]
    return out


def mangle(s):
    out = []
    for ch in s:
        if ch == "/":
            out.append("_")
        elif ch == "_":
            out.append("_1")
        elif ch == ";":
            out.append("_2")
        elif ch == "[":
            out.append("_3")
        elif ch.isascii() and ch.isalnum():
            out.append(ch)
        else:
            b = ch.encode("utf-16-le")
            for k in range(0, len(b), 2):
                out.append("_0%04x" % struct.unpack_from("<H", b, k)[0])
    return "".join(out)


def jni_short(cls, method):
    return "Java_" + mangle(cls) + "_" + mangle(method)


def jni_long(cls, method, desc):
    return jni_short(cls, method) + "__" + mangle(desc[1:desc.index(")")])


def resolve(exports, natives):
    """exports: nomes Java_*; natives: [(classe, metodo, descritor)]. Retorna [(export, letras)]
    na ordem de exports. GenError cita todos os nomes sem metodo native correspondente."""
    short, long_ = {}, {}
    for c, m, d in natives:
        short.setdefault(jni_short(c, m), []).append((c, m, d))
        long_.setdefault(jni_long(c, m, d), []).append((c, m, d))
    out, missing = [], []
    for e in exports:
        cand = long_.get(e) or short.get(e)
        if not cand:
            missing.append(e)
        elif len(cand) > 1:
            raise GenError("export ambiguo (sobrecarga sem nome longo): " + e)
        else:
            out.append((e, arg_letters(cand[0][2])))
    if missing:
        raise GenError("sem metodo native correspondente no jar: " + ", ".join(missing))
    return out


def jar_natives(jar_path, exports):
    z = zipfile.ZipFile(jar_path)
    seen, out = set(), []
    for nm in z.namelist():
        if not nm.endswith(".class"):
            continue
        pre = "Java_" + mangle(nm[:-6]) + "_"
        if not any(e.startswith(pre) for e in exports):
            continue
        cname, meths = parse_class(z.read(nm))
        if cname in seen:
            continue
        seen.add(cname)
        out += [(cname, m, d) for m, d, f in meths if f & ACC_NATIVE]
    return out


def generate(lib, dylib_path, jar_path, out_dir):
    data = open(dylib_path, "rb").read()
    info = parse_macho(data)
    exports = [e for e in info["exports"] if e.startswith("Java_")]
    table = resolve(exports, jar_natives(jar_path, exports))
    sha = hashlib.sha256(data).hexdigest()
    os.makedirs(out_dir, exist_ok=True)
    h = ["/* gerado por tools/gen_bridge.py - nao editar */",
         "#define BRIDGE_COUNT %d" % len(table),
         "#define BRIDGE_DYLIB_SIZE %du" % len(data),
         '#define BRIDGE_DYLIB_SHA256 "%s"' % sha,
         "static const struct { const char* name; const char* letters; } g_table[BRIDGE_COUNT] = {"]
    h += ['    { "%s", "%s" },' % t for t in table]
    h.append("};")
    s = ["// gerado por tools/gen_bridge.py - nao editar", "    .text", "    .align 2"]
    for i, (name, _) in enumerate(table):
        s += ["    .globl %s" % name, "%s:" % name,
              "    adrp x16, g_targets+%d" % (8 * i),
              "    ldr x16, [x16, :lo12:g_targets+%d]" % (8 * i),
              "    br x16"]
    d = ["LIBRARY %s.dll" % lib, "EXPORTS", "    JNI_OnLoad"] + ["    " + t[0] for t in table]
    for fn, lines in ((lib + "_table.h", h), (lib + "_thunks.S", s), (lib + ".def", d)):
        with open(os.path.join(out_dir, fn), "w", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
    return table


def main(argv=None):
    ap = argparse.ArgumentParser()
    for a in ("lib", "dylib", "jar", "out"):
        ap.add_argument("--" + a, required=True)
    a = ap.parse_args(argv)
    try:
        t = generate(a.lib, a.dylib, a.jar, a.out)
    except GenError as e:
        print("gen_bridge: erro: %s" % e, file=sys.stderr)
        return 1
    print("gen_bridge: %d entradas Java_* em %s" % (len(t), a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
