import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import check_fmod  # noqa: E402

GAME = os.environ.get("PZ_GAME_DIR", r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")
CORE = os.path.join(ROOT, "third-party", "fmod", "fmod.dll")
STUDIO = os.path.join(ROOT, "third-party", "fmod", "fmodstudio.dll")
DYLIB = os.path.join(ROOT, "game-libs", "macos-42.21", "libfmodintegration.dylib")


@unittest.skipUnless(os.path.exists(CORE) and os.path.exists(STUDIO), "third-party/fmod ausente")
class T(unittest.TestCase):
    def test_detects_missing(self):
        r = check_fmod.check_fmod(CORE, STUDIO, ["FMOD_System_Create", "FMOD_Nao_Existe"])
        self.assertEqual(r["missing"], ["FMOD_Nao_Existe"])
        self.assertEqual(r["arch"], "arm64")

    def test_real_dylib_imports_present(self):
        imps = check_fmod.dylib_c_imports(DYLIB)
        self.assertEqual(len(imps), 367)
        r = check_fmod.check_fmod(CORE, STUDIO, imps)
        self.assertEqual(r["missing"], [])
        self.assertTrue(r["version"].startswith("2.3.9"))

    @unittest.skipUnless(os.path.exists(os.path.join(GAME, "fmod.dll")), "jogo ausente")
    def test_rejects_x64(self):
        r = check_fmod.check_fmod(os.path.join(GAME, "fmod.dll"), os.path.join(GAME, "fmodstudio.dll"), [])
        self.assertEqual(r["arch"], "x64")


if __name__ == "__main__":
    unittest.main()
