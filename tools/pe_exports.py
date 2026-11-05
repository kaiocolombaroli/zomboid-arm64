"""Lista nomes exportados por uma DLL PE, somente stdlib."""
import struct


def pe_exports(path: str) -> list:
    with open(path, "rb") as f:
        d = f.read()
    try:
        return _pe_exports(d)
    except (IndexError, struct.error):
        raise ValueError("PE truncado") from None


def _pe_exports(d):
    if len(d) < 0x40:
        raise ValueError("PE truncado")
    pe = struct.unpack_from("<I", d, 0x3C)[0]
    if d[pe:pe + 4] != b"PE\0\0":
        raise ValueError("nao e PE")
    nsec, = struct.unpack_from("<H", d, pe + 6)
    optsz, = struct.unpack_from("<H", d, pe + 20)
    opt = pe + 24
    magic, = struct.unpack_from("<H", d, opt)
    ddir = opt + (112 if magic == 0x20B else 96)
    rva, _size = struct.unpack_from("<II", d, ddir)
    if not rva:
        return []
    secs = []
    s = opt + optsz
    for i in range(nsec):
        vsz, va, rsz, raw = struct.unpack_from("<IIII", d, s + 40 * i + 8)
        secs.append((va, max(vsz, rsz), raw))

    def off(r):
        for va, sz, raw in secs:
            if va <= r < va + sz:
                return r - va + raw
        raise ValueError("RVA invalido")

    e = off(rva)
    n_names, = struct.unpack_from("<I", d, e + 24)
    names_rva, = struct.unpack_from("<I", d, e + 32)
    no = off(names_rva)
    out = []
    for i in range(n_names):
        r, = struct.unpack_from("<I", d, no + 4 * i)
        o = off(r)
        out.append(d[o:d.index(b"\0", o)].decode("ascii", "replace"))
    return out


if __name__ == "__main__":
    import sys
    print("\n".join(pe_exports(sys.argv[1])))
