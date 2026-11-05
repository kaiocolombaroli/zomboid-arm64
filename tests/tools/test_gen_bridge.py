import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import gen_bridge  # noqa: E402
from gen_bridge import arg_letters, mangle, resolve, GenError  # noqa: E402

DYLIB = os.path.join(ROOT, "game-libs", "macos-42.21", "libLighting.dylib")
JAR = os.environ.get("PZ_JAR", r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid\projectzomboid.jar")


class T(unittest.TestCase):
    def test_arg_letters(self):
        self.assertEqual(arg_letters("(IFZ[BJ)V"), "ppifCpI")
        self.assertEqual(arg_letters("(Ljava/lang/String;BSCD)I"), "pppcwWd")
        self.assertEqual(arg_letters("()V"), "pp")
        self.assertEqual(arg_letters("([[ILjava/lang/Object;)V"), "pppp")

    def test_mangle(self):
        self.assertEqual(mangle("a/b_c;[$"), "a_b_1c_2_3_00024")

    def test_overload_uses_long_name(self):
        nat = [("p/C", "f", "(I)V"), ("p/C", "f", "(F)V")]
        self.assertEqual(resolve(["Java_p_C_f__F"], nat), [("Java_p_C_f__F", "ppf")])
        with self.assertRaises(GenError):
            resolve(["Java_p_C_f"], nat)

    def test_missing_signature_fails(self):
        with self.assertRaises(GenError) as cm:
            resolve(["Java_x_Y_nope"], [("x/Y", "other", "()V")])
        self.assertIn("Java_x_Y_nope", str(cm.exception))

    @unittest.skipUnless(os.path.exists(DYLIB), "dylib ausente")
    def test_cli_missing_signature_fails(self):
        with tempfile.TemporaryDirectory() as d:
            jar = os.path.join(d, "empty.jar")
            zipfile.ZipFile(jar, "w").close()
            r = subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tools", "gen_bridge.py"),
                                "--lib", "L", "--dylib", DYLIB, "--jar", jar, "--out", d],
                               capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("Java_zombie_iso_LightingJNI_configure", r.stderr)

    @unittest.skipUnless(os.path.exists(DYLIB) and os.path.exists(JAR), "dylib/jar ausentes")
    def test_thunks_cover_all_exports(self):
        from macho_inventory import parse_macho
        exp = [e for e in parse_macho(open(DYLIB, "rb").read())["exports"] if e.startswith("Java_")]
        with tempfile.TemporaryDirectory() as d:
            gen_bridge.generate("Lighting64", DYLIB, JAR, d)
            defn = open(os.path.join(d, "Lighting64.def")).read().split()
            asm = open(os.path.join(d, "Lighting64_thunks.S")).read().splitlines()
            for e in exp:
                self.assertEqual(defn.count(e), 1, e)
                self.assertEqual(asm.count("%s:" % e), 1, e)

    def test_array_of_objects_letter(self):
        self.assertEqual(arg_letters("([Ljava/lang/String;)V"), "ppp")
        self.assertEqual(arg_letters("([[Ljava/lang/String;I)V"), "pppi")

    def test_instance_and_static_both_get_pp(self):
        # JNIEnv* e jclass/jobject: o segundo argumento e sempre p, estatico ou de instancia
        self.assertTrue(arg_letters("(I)V").startswith("pp"))
        self.assertEqual(arg_letters("()V"), "pp")

    def test_underscore_in_names_uses_1(self):
        self.assertEqual(mangle("a_b"), "a_1b")
        self.assertEqual(resolve(["Java_p_C_do_1it"], [("p/C", "do_it", "(I)V")]), [("Java_p_C_do_1it", "ppi")])

    @unittest.skipUnless(os.path.exists(JAR), "jar ausente")
    def test_table_def_same_order_and_all_libs_cover_exports(self):
        from macho_inventory import parse_macho
        libs = {"PZBullet64": ("libPZBullet.dylib", 98), "PZClipper64": ("libPZClipper.dylib", 19),
                "PZPathFind64": ("libPZPathFind.dylib", 13), "PZPopMan64": ("libPZPopMan.dylib", 59)}
        gdir = os.path.join(ROOT, "game-libs", "macos-42.21")
        for lib, (fn, want) in libs.items():
            path = os.path.join(gdir, fn)
            if not os.path.exists(path):
                self.skipTest(path)
            exp = [e for e in parse_macho(open(path, "rb").read())["exports"] if e.startswith("Java_")]
            self.assertEqual(len(exp), want, lib)
            with tempfile.TemporaryDirectory() as d:
                table = gen_bridge.generate(lib, path, JAR, d)
                self.assertEqual(len(table), want, lib)
                h = open(os.path.join(d, lib + "_table.h")).read()
                names_h = [l.split('"')[1] for l in h.splitlines() if l.strip().startswith("{ \"")]
                defs = open(os.path.join(d, lib + ".def")).read().split()
                names_def = defs[defs.index("JNI_OnLoad") + 1:]
                asm = open(os.path.join(d, lib + "_thunks.S")).read().splitlines()
                names_s = [l[:-1] for l in asm if l.startswith("Java_") and l.endswith(":")]
                self.assertEqual(names_h, names_def, lib)
                self.assertEqual(names_h, names_s, lib)
                self.assertEqual(sorted(names_h), sorted(exp), lib)


if __name__ == "__main__":
    unittest.main()
