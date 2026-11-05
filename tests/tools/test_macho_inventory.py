import os
import struct
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from macho_inventory import parse_macho  # noqa: E402
from pe_exports import pe_exports  # noqa: E402

ARM64 = 0x0100000C
X86_64 = 0x01000007
JVM = os.path.join(ROOT, "spike-java-arm", "jdk-25-arm64", "bin", "server", "jvm.dll")


def thin(cputype, cpusubtype, cmds=b"", ncmds=0):
    hdr = struct.pack("<IiIIIIII", 0xFEEDFACF, cputype, cpusubtype, 6, ncmds, len(cmds), 0, 0)
    return hdr + cmds


def fat(slices):
    n = len(slices)
    pos = (8 + 20 * n + 15) & ~15
    head, blobs = struct.pack(">II", 0xCAFEBABE, n), b""
    for ct, cs, blob in slices:
        head += struct.pack(">iiIII", ct, cs, pos + len(blobs), len(blob), 4)
        blobs += blob
    return head + b"\0" * (pos - len(head)) + blobs


def dyld_info(bind):
    """Mach-O fino com LC_DYLD_INFO_ONLY cujo bind stream e `bind`."""
    off = 32 + 48
    cmd = struct.pack("<IIIIIIIIIIII", 0x80000022, 48, 0, 0, off, len(bind), 0, 0, 0, 0, 0, 0)
    return thin(ARM64, 0, cmd, 1) + bind


def trie_macho(blob):
    cmd = struct.pack("<IIII", 0x80000033, 16, 32 + 16, len(blob))
    return thin(ARM64, 0, cmd, 1) + blob


class TestMacho(unittest.TestCase):
    def test_fat_header_finds_arm64(self):
        r = parse_macho(fat([(X86_64, 3, thin(X86_64, 3)), (ARM64, 0, thin(ARM64, 0))]))
        self.assertTrue(r["arm64_present"])
        self.assertFalse(r["arm64e"])

    def test_thin_arm64(self):
        r = parse_macho(thin(ARM64, 0))
        self.assertTrue(r["arm64_present"])
        self.assertFalse(r["arm64e"])

    def test_thin_arm64e(self):
        r = parse_macho(thin(ARM64, 2))
        self.assertTrue(r["arm64_present"])
        self.assertTrue(r["arm64e"])

    def test_chained_fixups_detected(self):
        cmd = struct.pack("<IIII", 0x80000034, 16, 0, 0)
        r = parse_macho(thin(ARM64, 0, cmd, 1))
        self.assertTrue(r["chained_fixups"])

    def test_real_lighting(self):
        path = os.path.join(ROOT, "game-libs", "macos-42.21", "libLighting.dylib")
        if not os.path.exists(path):
            self.skipTest("dylib ausente")
        with open(path, "rb") as f:
            r = parse_macho(f.read())
        self.assertIn("Java_zombie_iso_LightingJNI_squareSetLightTransmission", r["exports"])

    def test_bind_opcodes_a0_c0(self):
        stream = (b"\x40_first\0" + b"\xA0\x05"            # DO_BIND_ADD_ADDR_ULEB: 1 ULEB
                  + b"\x40_second\0" + b"\xC0\x03\x04"      # TIMES_SKIPPING_ULEB: 2 ULEB
                  + b"\x40_third\0" + b"\x90")
        r = parse_macho(dyld_info(stream))
        self.assertEqual(r["imports"], ["first", "second", "third"])

    def test_export_trie_with_branches(self):
        leaf = b"\x02\x00\x10\x00"  # info size 2 (flags, addr), 0 filhos
        root_len = 1 + 1 + len(b"_foo\0") + 1
        mid_len = 1 + 1 + len(b"bar\0") + 1 + len(b"baz\0") + 1
        mid, l1, l2 = root_len, root_len + mid_len, root_len + mid_len + len(leaf)
        root = b"\x00\x01_foo\0" + bytes([mid])
        midn = b"\x00\x02bar\0" + bytes([l1]) + b"baz\0" + bytes([l2])
        r = parse_macho(trie_macho(root + midn + leaf + leaf))
        self.assertEqual(r["exports"], ["foobar", "foobaz"])

    def test_chained_fixups_import_names(self):
        syms = b"\0_foo\0_bar\0"
        imps = struct.pack("<II", (1 << 9) | 1, (6 << 9) | 1)
        blob = struct.pack("<7I", 0, 28, 28, 28 + len(imps), 2, 1, 0) + imps + syms
        cmd = struct.pack("<IIII", 0x80000034, 16, 32 + 16, len(blob))
        r = parse_macho(thin(ARM64, 0, cmd, 1) + blob)
        self.assertTrue(r["chained_fixups"])
        self.assertEqual(r["imports"], ["bar", "foo"])

    def test_truncated_raises_valueerror(self):
        data = dyld_info(b"\x40_first\0\x90")
        for n in range(8, len(data)):
            with self.assertRaises(ValueError, msg=str(n)):
                parse_macho(data[:n])
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "t.bin")
            for n in range(0, len(data)):
                with open(path, "wb") as f:
                    f.write(data[:n])
                with self.assertRaises(ValueError, msg=str(n)):
                    pe_exports(path)
            if os.path.exists(JVM):
                with open(JVM, "rb") as f:
                    full = f.read()
                for n in (0, 10, 0x3C, 0x80, 0x200, 0x1000):
                    with open(path, "wb") as f:
                        f.write(full[:n])
                    try:
                        pe_exports(path)
                    except ValueError:
                        pass

    def test_pe_exports_jvm(self):
        if not os.path.exists(JVM):
            self.skipTest("jvm.dll ausente")
        self.assertIn("JNI_CreateJavaVM", pe_exports(JVM))


def unwind_macho(common, pages):
    """Mach-O fino arm64 com __TEXT,__unwind_info. `pages`: lista de
    ('reg', [(func, enc)]) ou ('cmp', base, [(delta, idx)], [enc_pagina])."""
    n = len(pages)
    idx_off = 28 + 4 * len(common)
    pg_base = idx_off + 12 * (n + 1)
    body, entries = b"", []
    for pg in pages:
        pos = pg_base + len(body)
        if pg[0] == "reg":
            ents = pg[1]
            hdr = struct.pack("<IHH", 2, 8, len(ents))
            body += hdr + b"".join(struct.pack("<II", f, e) for f, e in ents)
            entries.append((ents[0][0], pos))
        else:
            _k, base, ents, penc = pg
            eo, nobj = 12, len(ents)
            hdr = struct.pack("<IHHHH", 3, eo, nobj, eo + 4 * nobj, len(penc))
            body += hdr + b"".join(struct.pack("<I", (i << 24) | d) for d, i in ents)
            body += b"".join(struct.pack("<I", e) for e in penc)
            entries.append((base, pos))
    index = b"".join(struct.pack("<III", f, p, 0) for f, p in entries)
    index += struct.pack("<III", 0xFFFF, 0, 0)
    u = struct.pack("<7I", 1, 28, len(common), idx_off, 0, idx_off, n + 1)
    u += b"".join(struct.pack("<I", c) for c in common) + index + body
    seg_sz = 72 + 80
    sect_off = 32 + seg_sz
    seg = struct.pack("<II16sQQQQiiII", 0x19, seg_sz, b"__TEXT", 0, 0x4000, 0, len(u),
                      5, 5, 1, 0)
    sect = struct.pack("<16s16sQQIIIIIIII", b"__unwind_info", b"__TEXT", 0, len(u),
                       sect_off, 2, 0, 0, 0, 0, 0, 0)
    return thin(ARM64, 0, seg + sect, 1) + u


class UnwindStatsTests(unittest.TestCase):
    FRAME, FRAMELESS, DWARF = 0x04000001, 0x02001000, 0x03000000
    LSDA = 0x40000000

    def test_unwind_stats_synthetic(self):
        from macho_inventory import unwind_dwarf_lsda_functions, unwind_stats
        common = [self.FRAME, self.FRAMELESS]
        pages = [
            ("cmp", 0, [(0, 0), (16, 1), (32, 2)], [self.FRAME | self.LSDA]),
            ("reg", [(0x100, self.DWARF | self.LSDA), (0x110, self.DWARF)]),
        ]
        st = unwind_stats(unwind_macho(common, pages))
        self.assertEqual(st["frame"], 2)
        self.assertEqual(st["frameless"], 1)
        self.assertEqual(st["dwarf"], 2)
        self.assertEqual(st["with_lsda"], 2)
        self.assertEqual(st["dwarf_with_lsda"], 1)
        self.assertEqual((st["pages_regular"], st["pages_compressed"]), (1, 1))
        self.assertEqual(unwind_dwarf_lsda_functions(unwind_macho(common, pages)), [0x100])

    def test_unwind_stats_without_section(self):
        from macho_inventory import unwind_stats
        st = unwind_stats(thin(ARM64, 0))
        self.assertEqual(st["frame"] + st["dwarf"] + st["pages_compressed"], 0)


if __name__ == "__main__":
    unittest.main()
