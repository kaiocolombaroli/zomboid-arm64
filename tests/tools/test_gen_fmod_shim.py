import glob
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import gen_fmod_shim  # noqa: E402

API = os.environ.get("FMOD_API", gen_fmod_shim.FMOD_API)
DYLIB = gen_fmod_shim.DEFAULT_DYLIB
CLANG = (glob.glob(os.path.join(ROOT, "tools", "toolchain", "llvm-mingw", "*", "bin", "clang.exe")) or [None])[0]


@unittest.skipUnless(os.path.isdir(API) and os.path.exists(DYLIB), "cabecalhos FMOD ou dylib ausentes")
class T(unittest.TestCase):
    def test_generated_file_is_current(self):
        text, rows, direct = gen_fmod_shim.generate(API, DYLIB)
        with open(gen_fmod_shim.DEFAULT_OUT, encoding="utf-8", newline="") as f:
            self.assertEqual(f.read(), text)
        self.assertEqual(len(rows), 61)
        self.assertEqual(len(direct), 367)

    def test_kinds_of_itanium_symbols(self):
        parts, kinds, const = gen_fmod_shim.demangle_method(
            "_ZN4FMOD6Studio13EventInstance16setParameterByIDE24FMOD_STUDIO_PARAMETER_IDfb")
        self.assertEqual(parts, ["FMOD", "Studio", "EventInstance", "setParameterByID"])
        self.assertEqual(kinds, ["N", "f", "b"])
        self.assertFalse(const)
        parts, kinds, const = gen_fmod_shim.demangle_method("_ZNK4FMOD6Studio13EventInstance9getPausedEPb")
        self.assertEqual((kinds, const), (["P"], True))
        _, kinds, _ = gen_fmod_shim.demangle_method(
            "_ZN4FMOD6Studio13EventInstance11setCallbackEPF11FMOD_RESULTjP25FMOD_STUDIO_EVENTINSTANCEPvEj")
        self.assertEqual(kinds, ["P", "j"])
        _, kinds, _ = gen_fmod_shim.demangle_method("_ZN4FMOD6Studio13EventInstance5startEv")
        self.assertEqual(kinds, [])

    def test_signature_mismatch_aborts(self):
        # um simbolo cujo tipo diverge do .hpp (setVolume recebendo int em vez de float) tem de abortar a geracao
        hpp = gen_fmod_shim.parse_hpp(API)
        kinds = gen_fmod_shim.typedef_kinds(API)
        want = [gen_fmod_shim.hpp_kind(t, kinds) for t, _ in hpp["EventInstance"]["setVolume"]["params"]]
        self.assertEqual(want, ["f"])
        _, got, _ = gen_fmod_shim.demangle_method("_ZN4FMOD6Studio13EventInstance9setVolumeEi")
        self.assertNotEqual(want, got)

    def test_every_shim_target_is_an_export(self):
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        from pe_exports import pe_exports
        have = set()
        for dll in ("fmod.dll", "fmodstudio.dll"):
            p = os.path.join(ROOT, "third-party", "fmod", dll)
            if not os.path.exists(p):
                self.skipTest("third-party/fmod ausente")
            have |= set(pe_exports(p))
        text, rows, direct = gen_fmod_shim.generate(API, DYLIB)
        self.assertEqual([n for n in direct if n not in have], [])
        self.assertEqual([r[5] for r in rows if r[5] not in have], [])

    @unittest.skipUnless(CLANG, "clang ausente")
    def test_struct_layouts_equal_apple_and_windows(self):
        # Sem `long` na API: todos os registros dos cabecalhos FMOD tem o mesmo tamanho/offsets em
        # arm64-apple-macos e aarch64-windows (inclui FMOD_ADVANCEDSETTINGS 104, FMOD_CREATESOUNDEXINFO 224,
        # FMOD_STUDIO_ADVANCEDSETTINGS 32, FMOD_3D_ATTRIBUTES 48, FMOD_GUID 16).
        with tempfile.TemporaryDirectory() as d:
            h = os.path.join(d, "all.h")
            with open(h, "w") as f:
                f.write('#include "fmod.h"\n#include "fmod_studio.h"\n')
            inc = ["-I" + os.path.join(API, "core", "inc"), "-I" + os.path.join(API, "studio", "inc")]
            outs = []
            for tgt, extra in (("arm64-apple-macos11", ["-ffreestanding"]), ("aarch64-w64-mingw32", [])):
                r = subprocess.run([CLANG, "--target=" + tgt, *extra, "-x", "c", "-fsyntax-only", "-Xclang",
                                    "-fdump-record-layouts-complete", *inc, h], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stderr)
                outs.append(r.stdout)
            self.assertEqual(outs[0], outs[1])
            for name, size in (("FMOD_ADVANCEDSETTINGS", 104), ("FMOD_CREATESOUNDEXINFO", 224),
                               ("FMOD_STUDIO_ADVANCEDSETTINGS", 32), ("FMOD_3D_ATTRIBUTES", 48), ("FMOD_GUID", 16)):
                i = outs[1].index("struct %s\n" % name)
                self.assertIn("sizeof=%d," % size, outs[1][i:i + 6000].split("*** Dumping")[0], name)


if __name__ == "__main__":
    unittest.main()
