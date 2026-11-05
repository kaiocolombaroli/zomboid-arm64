#!/usr/bin/env python3
"""Lista a arquitetura (header PE) de cada .dll/.exe em uma pasta. Uso: python -I pe_arch.py <pasta>"""
import os, struct, sys
from collections import Counter

MACHINES = {0x8664: "x64", 0xAA64: "arm64", 0xA641: "arm64ec", 0xA64E: "arm64x", 0x14C: "x86"}

def machine(path):
    try:
        with open(path, "rb") as f:
            h = f.read(64)
            if len(h) < 64 or h[:2] != b"MZ":
                return "nao-PE"
            off = struct.unpack_from("<I", h, 0x3C)[0]
            f.seek(off)
            sig = f.read(6)
            if len(sig) < 6 or sig[:4] != b"PE\0\0":
                return "nao-PE"
            m = struct.unpack_from("<H", sig, 4)[0]
            return MACHINES.get(m, "desconhecido(0x%04X)" % m)
    except OSError as e:
        return "erro(%s)" % e

def main():
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        sys.exit("uso: python -I pe_arch.py <pasta>")
    root = sys.argv[1]
    counts = Counter()
    for d, _, files in os.walk(root):
        for n in sorted(files):
            if n.lower().endswith((".dll", ".exe")):
                p = os.path.join(d, n)
                a = machine(p)
                counts[a] += 1
                print("%-10s %s" % (a, os.path.relpath(p, root)))
    print("\nResumo:")
    for a, c in counts.most_common():
        print("  %s: %d" % (a, c))

if __name__ == "__main__":
    main()
