"""Inventario de binarios Mach-O (fat ou fino), somente stdlib."""
import struct

FAT_MAGIC = 0xCAFEBABE
FAT_MAGIC_64 = 0xCAFEBABF
MH_MAGIC_64 = 0xFEEDFACF
CPU_TYPE_ARM64 = 0x0100000C
LC_SEGMENT_64 = 0x19
LC_LOAD_DYLIBS = {0xC, 0x80000018, 0x8000001F, 0x20, 0x23}
LC_DYLD_INFO, LC_DYLD_INFO_ONLY = 0x22, 0x80000022
LC_DYLD_EXPORTS_TRIE = 0x80000033
LC_DYLD_CHAINED_FIXUPS = 0x80000034
S_THREAD_LOCAL_VARIABLES = 0x13


def _uleb(d, p):
    r = s = 0
    while True:
        b = d[p]
        p += 1
        r |= (b & 0x7F) << s
        s += 7
        if not b & 0x80:
            return r, p


def _cstr(d, p):
    e = d.index(b"\0", p)
    return d[p:e].decode("utf-8", "replace"), e + 1


def _check(d, off, size):
    if off < 0 or size < 0 or off + size > len(d):
        raise ValueError("Mach-O truncado")


def _strip(n):
    return n[1:] if n.startswith("_") else n


def _slices(data):
    """Lista de (cputype, cpusubtype, bytes) das fatias."""
    if len(data) < 8:
        return []
    magic = struct.unpack(">I", data[:4])[0]
    if magic in (FAT_MAGIC, FAT_MAGIC_64):
        n = struct.unpack(">I", data[4:8])[0]
        out, p = [], 8
        for _ in range(n):
            if magic == FAT_MAGIC:
                ct, cs, off, size, _a = struct.unpack(">iiIII", data[p:p + 20])
                p += 20
            else:
                ct, cs, off, size, _a, _r = struct.unpack(">iiQQII", data[p:p + 32])
                p += 32
            _check(data, off, size)
            out.append((ct & 0xFFFFFFFF, cs & 0xFFFFFFFF, data[off:off + size]))
        return out
    if struct.unpack("<I", data[:4])[0] == MH_MAGIC_64:
        ct, cs = struct.unpack("<iI", data[4:12])
        return [(ct & 0xFFFFFFFF, cs, data)]
    return []


def _trie_exports(d, off, size):
    _check(d, off, size)
    names, seen, stack = [], set(), [(off, "")]
    end = off + size
    while stack:
        node, prefix = stack.pop()
        if node in seen or node >= end:
            continue
        seen.add(node)
        tsize, p = _uleb(d, node)
        if tsize:
            names.append(_strip(prefix))
        p += tsize
        cnt = d[p]
        p += 1
        for _ in range(cnt):
            s, p = _cstr(d, p)
            child, p = _uleb(d, p)
            stack.append((off + child, prefix + s))
    return names


def _bind_imports(d, off, size):
    """Percorre opcodes de bind/weak/lazy e coleta nomes de simbolos."""
    _check(d, off, size)
    names, p, end = [], off, off + size
    while p < end:
        b = d[p]
        p += 1
        op, imm = b & 0xF0, b & 0x0F
        if op == 0x40:  # SET_SYMBOL_TRAILING_FLAGS_IMM: nome terminado em NUL
            sym, p = _cstr(d, p)
            names.append(_strip(sym))
        elif op in (0x20, 0x60, 0x70, 0x80, 0xA0):
            # 0x20 SET_DYLIB_ORDINAL_ULEB, 0x60 SET_ADDEND_SLEB (varint),
            # 0x70 SET_SEGMENT_AND_OFFSET_ULEB, 0x80 ADD_ADDR_ULEB,
            # 0xA0 DO_BIND_ADD_ADDR_ULEB: 1 varint cada
            _, p = _uleb(d, p)
        elif op == 0xC0:  # DO_BIND_ULEB_TIMES_SKIPPING_ULEB: 2 ULEB
            _, p = _uleb(d, p)
            _, p = _uleb(d, p)
        # 0x00 DONE, 0x10/0x30/0x50/0xB0 imediato, 0x90 DO_BIND: sem operando
        elif op == 0xD0 and imm == 0:  # THREADED / SET_BIND_ORDINAL_TABLE_SIZE_ULEB
            _, p = _uleb(d, p)
    return names


def _chained_imports(d, off, size):
    _check(d, off, size)
    if size < 28:
        return []
    (_v, _so, io, so, cnt, fmt, _sf) = struct.unpack("<7I", d[off:off + 28])
    names = []
    for i in range(cnt):
        if fmt in (1, 2):
            step = 4 if fmt == 1 else 8
            (v,) = struct.unpack("<I", d[off + io + step * i:off + io + step * i + 4])
            no = v >> 9
        elif fmt == 3:
            (v,) = struct.unpack("<Q", d[off + io + 16 * i:off + io + 16 * i + 8])
            no = v >> 32
        else:
            break
        names.append(_strip(_cstr(d, off + so + no)[0]))
    return names


def parse_macho(data: bytes) -> dict:
    try:
        return _parse_macho(data)
    except (IndexError, struct.error):
        raise ValueError("Mach-O truncado") from None


def _parse_macho(data):
    res = {"arm64_present": False, "arm64e": False, "dylibs": [],
           "chained_fixups": False, "exports": [], "imports": [],
           "tlv": False, "sections": []}
    slices = _slices(data)
    if not slices:
        return res
    chosen = None
    for ct, cs, blob in slices:
        if ct == CPU_TYPE_ARM64:
            res["arm64_present"] = True
            is_e = (cs & 0xFFFFFF) == 2
            if is_e:
                res["arm64e"] = True
            if chosen is None or not is_e:
                chosen = blob
    if chosen is None:
        chosen = slices[0][2]
    d = chosen
    if len(d) >= 4 and struct.unpack("<I", d[:4])[0] != MH_MAGIC_64:
        return res
    if len(d) < 32:
        raise ValueError("Mach-O truncado")
    ncmds = struct.unpack("<I", d[16:20])[0]
    p = 32
    imports, exports = [], []
    for _ in range(ncmds):
        cmd, csize = struct.unpack("<II", d[p:p + 8])
        _check(d, p, max(csize, 8))
        if cmd == LC_SEGMENT_64:
            segname = d[p + 8:p + 24].rstrip(b"\0").decode("ascii", "replace")
            nsects = struct.unpack("<I", d[p + 64:p + 68])[0]
            q = p + 72
            for _s in range(nsects):
                sect = d[q:q + 16].rstrip(b"\0").decode("ascii", "replace")
                flags = struct.unpack("<I", d[q + 64:q + 68])[0]
                res["sections"].append(segname + "," + sect)
                if (flags & 0xFF) == S_THREAD_LOCAL_VARIABLES:
                    res["tlv"] = True
                q += 80
        elif cmd in LC_LOAD_DYLIBS:
            noff = struct.unpack("<I", d[p + 8:p + 12])[0]
            res["dylibs"].append(_cstr(d, p + noff)[0])
        elif cmd in (LC_DYLD_INFO, LC_DYLD_INFO_ONLY):
            (_ro, _rs, bo, bs, wo, ws, lo, ls, eo, es) = struct.unpack("<10I", d[p + 8:p + 48])
            for o, s in ((bo, bs), (wo, ws), (lo, ls)):
                if s:
                    imports += _bind_imports(d, o, s)
            if es:
                exports += _trie_exports(d, eo, es)
        elif cmd == LC_DYLD_EXPORTS_TRIE:
            eo, es = struct.unpack("<II", d[p + 8:p + 16])
            if es:
                exports += _trie_exports(d, eo, es)
        elif cmd == LC_DYLD_CHAINED_FIXUPS:
            res["chained_fixups"] = True
            fo, fs = struct.unpack("<II", d[p + 8:p + 16])
            if fs:
                imports += _chained_imports(d, fo, fs)
        p += csize
    res["imports"] = sorted(set(imports))
    res["exports"] = sorted(set(exports))
    return res


# --- compact unwind (__TEXT,__unwind_info) ---------------------------------------
UNWIND_HAS_LSDA = 0x40000000
UNWIND_ARM64_MODE_MASK = 0x0F000000
UNWIND_ARM64_MODE_FRAMELESS = 0x02000000
UNWIND_ARM64_MODE_DWARF = 0x03000000
UNWIND_ARM64_MODE_FRAME = 0x04000000


def _arm64_slice(data):
    slices = _slices(data)
    chosen = None
    for ct, cs, blob in slices:
        if ct == CPU_TYPE_ARM64 and (chosen is None or (cs & 0xFFFFFF) != 2):
            chosen = blob
    if chosen is None and slices:
        chosen = slices[0][2]
    return chosen


def _find_section(d, segname, sectname):
    """(offset, size) de uma secao no arquivo, ou None."""
    ncmds = struct.unpack("<I", d[16:20])[0]
    p = 32
    for _ in range(ncmds):
        cmd, csize = struct.unpack("<II", d[p:p + 8])
        _check(d, p, max(csize, 8))
        if cmd == LC_SEGMENT_64:
            nsects = struct.unpack("<I", d[p + 64:p + 68])[0]
            q = p + 72
            for _s in range(nsects):
                sn = d[q:q + 16].rstrip(b"\0").decode("ascii", "replace")
                sg = d[q + 16:q + 32].rstrip(b"\0").decode("ascii", "replace")
                if sn == sectname and sg == segname:
                    size, off = struct.unpack("<QI", d[q + 40:q + 52])
                    return off, size
                q += 80
        p += csize
    return None


def unwind_stats(data: bytes) -> dict:
    """Estatisticas do compact unwind (`__unwind_info`) de um Mach-O arm64.

    Chaves: frame, frameless, dwarf, with_lsda, pages_regular, pages_compressed
    (contagens por funcao/pagina) mais `other` (modo 0 ou desconhecido) e
    `dwarf_with_lsda` (funcoes com modo DWARF e LSDA ao mesmo tempo).
    """
    try:
        return _unwind_stats(data)
    except (IndexError, struct.error):
        raise ValueError("Mach-O truncado") from None


def _unwind_stats(data):
    res = {"frame": 0, "frameless": 0, "dwarf": 0, "other": 0, "with_lsda": 0,
           "dwarf_with_lsda": 0, "pages_regular": 0, "pages_compressed": 0}
    d = _arm64_slice(data)
    if d is None or len(d) < 32 or struct.unpack("<I", d[:4])[0] != MH_MAGIC_64:
        return res
    sec = _find_section(d, "__TEXT", "__unwind_info")
    if not sec or not sec[1]:
        return res
    off, size = sec
    _check(d, off, size)
    u = d[off:off + size]
    (_ver, ce_off, ce_cnt, _po, _pc, idx_off, idx_cnt) = struct.unpack("<7I", u[:28])
    common = struct.unpack("<%dI" % ce_cnt, u[ce_off:ce_off + 4 * ce_cnt]) if ce_cnt else ()

    def count(enc):
        mode = enc & UNWIND_ARM64_MODE_MASK
        if mode == UNWIND_ARM64_MODE_FRAME:
            res["frame"] += 1
        elif mode == UNWIND_ARM64_MODE_FRAMELESS:
            res["frameless"] += 1
        elif mode == UNWIND_ARM64_MODE_DWARF:
            res["dwarf"] += 1
        else:
            res["other"] += 1
        if enc & UNWIND_HAS_LSDA:
            res["with_lsda"] += 1
            if mode == UNWIND_ARM64_MODE_DWARF:
                res["dwarf_with_lsda"] += 1

    # a ultima entrada do indice e a sentinela (pagina 0)
    for i in range(idx_cnt):
        _fo, pg, _lsda = struct.unpack("<III", u[idx_off + 12 * i:idx_off + 12 * i + 12])
        if not pg:
            continue
        kind, = struct.unpack("<I", u[pg:pg + 4])
        if kind == 2:  # regular
            res["pages_regular"] += 1
            epo, ecnt = struct.unpack("<HH", u[pg + 4:pg + 8])
            for j in range(ecnt):
                _f, enc = struct.unpack("<II", u[pg + epo + 8 * j:pg + epo + 8 * j + 8])
                count(enc)
        elif kind == 3:  # compressed
            res["pages_compressed"] += 1
            epo, ecnt, eno, enc_cnt = struct.unpack("<HHHH", u[pg + 4:pg + 12])
            pen = struct.unpack("<%dI" % enc_cnt, u[pg + eno:pg + eno + 4 * enc_cnt]) if enc_cnt else ()
            for j in range(ecnt):
                e, = struct.unpack("<I", u[pg + epo + 4 * j:pg + epo + 4 * j + 4])
                k = e >> 24
                if k < len(common):
                    count(common[k])
                elif k - len(common) < enc_cnt:
                    count(pen[k - len(common)])
                else:
                    raise ValueError("indice de encoding fora da faixa")
        else:
            raise ValueError("tipo de pagina de unwind desconhecido: %d" % kind)
    return res


def unwind_dwarf_lsda_functions(data: bytes) -> list:
    """Offsets (relativos a imagem) de funcoes com modo DWARF e LSDA."""
    d = _arm64_slice(data)
    out = []
    sec = _find_section(d, "__TEXT", "__unwind_info") if d else None
    if not sec or not sec[1]:
        return out
    off, size = sec
    u = d[off:off + size]
    (_v, ce_off, ce_cnt, _po, _pc, idx_off, idx_cnt) = struct.unpack("<7I", u[:28])
    common = struct.unpack("<%dI" % ce_cnt, u[ce_off:ce_off + 4 * ce_cnt]) if ce_cnt else ()
    for i in range(idx_cnt):
        fo, pg, _l = struct.unpack("<III", u[idx_off + 12 * i:idx_off + 12 * i + 12])
        if not pg:
            continue
        kind, = struct.unpack("<I", u[pg:pg + 4])
        if kind == 2:
            epo, ecnt = struct.unpack("<HH", u[pg + 4:pg + 8])
            for j in range(ecnt):
                f, enc = struct.unpack("<II", u[pg + epo + 8 * j:pg + epo + 8 * j + 8])
                if (enc & UNWIND_ARM64_MODE_MASK) == UNWIND_ARM64_MODE_DWARF and enc & UNWIND_HAS_LSDA:
                    out.append(f)
        elif kind == 3:
            epo, ecnt, eno, enc_cnt = struct.unpack("<HHHH", u[pg + 4:pg + 12])
            pen = struct.unpack("<%dI" % enc_cnt, u[pg + eno:pg + eno + 4 * enc_cnt]) if enc_cnt else ()
            for j in range(ecnt):
                e, = struct.unpack("<I", u[pg + epo + 4 * j:pg + epo + 4 * j + 4])
                k = e >> 24
                enc = common[k] if k < len(common) else pen[k - len(common)]
                if (enc & UNWIND_ARM64_MODE_MASK) == UNWIND_ARM64_MODE_DWARF and enc & UNWIND_HAS_LSDA:
                    out.append(fo + (e & 0xFFFFFF))
    return out


if __name__ == "__main__":
    import json
    import sys
    with open(sys.argv[1], "rb") as f:
        print(json.dumps(parse_macho(f.read()), indent=2))
