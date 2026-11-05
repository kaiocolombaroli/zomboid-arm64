import os
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import gen_stub  # noqa: E402
from pe_exports import pe_exports  # noqa: E402

GAME = os.environ.get("PZ_GAME_DIR", r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")
JAR = os.environ.get("PZ_JAR", os.path.join(GAME, "projectzomboid.jar"))
DIST = os.path.join(ROOT, "dist")
COUNTS = {"ZNetNoSteam64": 64, "ZNetJNI64": 139, "bink64": 11, "imgui-java64": 2129}
# Ponte real (M5 Tarefa 3): deixou de ser stub, mas exporta os mesmos Java_* da DLL do jogo.
BRIDGED = {"fmodintegration64": 437}
EMPTY = ["RakNet64", "steam_api64", "bink2w64"]
# DLLs FMOD oficiais ARM64 (M5 Tarefa 4): copiadas de third-party/fmod, nao sao mais vazias.
FMOD_REAL = ["fmod", "fmodstudio"]
ARM64 = 0xAA64


def machine(path):
    with open(path, "rb") as f:
        d = f.read()
    pe = struct.unpack_from("<I", d, 0x3C)[0]
    return struct.unpack_from("<H", d, pe + 4)[0]


def java_exports(path):
    return [e for e in pe_exports(path) if e.startswith("Java_")]


@unittest.skipUnless(os.path.exists(JAR), "jar ausente")
class T(unittest.TestCase):
    def test_stub_counts(self):
        for lib, n in COUNTS.items():
            self.assertEqual(len(java_exports(os.path.join(GAME, lib + ".dll"))), n, "x64 " + lib)
            self.assertEqual(len(java_exports(os.path.join(DIST, lib + ".dll"))), n, "stub " + lib)

    def test_bridged_not_stub(self):
        for lib, n in BRIDGED.items():
            want = java_exports(os.path.join(GAME, lib + ".dll"))
            got = java_exports(os.path.join(DIST, lib + ".dll"))
            self.assertEqual(len(want), n, "x64 " + lib)
            self.assertEqual(set(got), set(want), lib)
            self.assertEqual(machine(os.path.join(DIST, lib + ".dll")), ARM64, lib)
            self.assertIn(b"pzdarwin", open(os.path.join(DIST, lib + ".dll"), "rb").read().lower(), lib)
            self.assertIn("JNI_OnLoad", pe_exports(os.path.join(DIST, lib + ".dll")))

    def test_exports_match_dll(self):
        for lib in COUNTS:
            want = java_exports(os.path.join(GAME, lib + ".dll"))
            got = java_exports(os.path.join(DIST, lib + ".dll"))
            self.assertEqual(set(got), set(want), lib)
            self.assertEqual(len(got), len(set(got)), lib)

    def test_all_stubs_arm64_and_standalone(self):
        for lib in list(COUNTS) + EMPTY:
            p = os.path.join(DIST, lib + ".dll")
            self.assertEqual(machine(p), ARM64, lib)
            self.assertNotIn(b"pzdarwin", open(p, "rb").read().lower(), lib)

    def test_empty_dlls(self):
        for lib in EMPTY:
            p = os.path.join(DIST, lib + ".dll")
            self.assertTrue(os.path.exists(p), lib)
            self.assertEqual(machine(p), ARM64, lib)
            self.assertEqual(pe_exports(p), [], lib)
            self.assertNotIn(b"JNI_OnLoad", open(p, "rb").read(), lib)

    def test_fmod_dlls_are_real_arm64(self):
        third = os.path.join(ROOT, "third-party", "fmod")
        for lib in FMOD_REAL:
            src = os.path.join(third, lib + ".dll")
            if not os.path.exists(src):
                self.skipTest("third-party/fmod ausente")
            p = os.path.join(DIST, lib + ".dll")
            self.assertEqual(machine(p), ARM64, lib)
            self.assertEqual(open(p, "rb").read(), open(src, "rb").read(), lib)
            self.assertTrue(any(e.startswith("FMOD_") for e in pe_exports(p)), lib)

    def test_no_jni_onload_unless_x64_has_it(self):
        for lib in COUNTS:
            has = "JNI_OnLoad" in pe_exports(os.path.join(GAME, lib + ".dll"))
            self.assertEqual("JNI_OnLoad" in pe_exports(os.path.join(DIST, lib + ".dll")), has, lib)

    def test_native_methods_shape(self):
        nm = gen_stub.native_methods(JAR, ["zombie/core/textures/VideoTexture"])
        self.assertTrue(nm)
        for m in nm:
            self.assertTrue(m["jni_name"].startswith("Java_zombie_core_textures_VideoTexture_"))
            self.assertIn(")", m["descriptor"])
            self.assertEqual(m["ret"], m["descriptor"].split(")")[1])

    def test_generated_files_cover_exports(self):
        with tempfile.TemporaryDirectory() as d:
            pairs, missing = gen_stub.generate("bink64", os.path.join(GAME, "bink64.dll"), JAR, d)
            self.assertEqual(missing, [])
            self.assertEqual(len(pairs), 11)
            asm = open(os.path.join(d, "bink64_stub.S")).read().splitlines()
            for i, (name, _) in enumerate(pairs):
                k = asm.index(name + ":")
                self.assertEqual(asm[k + 1].strip(), "mov x16, #%d" % i)
                self.assertEqual(asm[k + 2].strip(), "b stub_common")

    def _run_harness(self, lib, env_log=None):
        log = os.path.join(ROOT, "logs", "stub-%s.log" % lib)
        if os.path.exists(log):
            os.remove(log)
        env = dict(os.environ)
        if env_log is not None:
            env["PZARM_STUB_LOG"] = env_log
        r = subprocess.run(["powershell", "-NoProfile", "-File",
                            os.path.join(ROOT, "tests", "harness", "run-stub.ps1"), "-Lib", lib],
                           capture_output=True, text=True, env=env, cwd=ROOT)
        return r, log

    def test_first_call_logged(self):
        # o harness chama cada metodo duas vezes: so uma linha por metodo
        r, log = self._run_harness("bink64")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        ok = [l.split()[2] for l in r.stdout.splitlines() if l.startswith("OK ")]
        self.assertGreaterEqual(len(ok), 3)
        lines = open(log, encoding="utf-8").read().splitlines()
        for name in ok:
            self.assertEqual(lines.count("[stub] bink64 " + name), 1, name)
        self.assertEqual(len(lines), len(set(lines)))

    def test_log_can_be_disabled(self):
        r, log = self._run_harness("bink64", env_log="0")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertFalse(os.path.exists(log))


if __name__ == "__main__":
    unittest.main()
