import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from classify_imports import (  # noqa: E402
    LIBS, TranslateError, build_rows, libcxx_exports, read_imports, translate_name)

TSV = os.path.join(ROOT, "native", "imports", "classes.tsv")


def load_tsv():
    with open(TSV, encoding="utf-8") as f:
        lines = f.read().splitlines()
    assert lines[0] == "nome_apple\tclasse\talvo"
    return [tuple(l.split("\t")) for l in lines[1:] if l]


class TranslateTests(unittest.TestCase):
    def test_translate_size_t(self):
        self.assertEqual(translate_name("_ZNSt3__112__next_primeEm"),
                         "_ZNSt3__112__next_primeEy")

    def test_translate_long(self):
        self.assertEqual(
            translate_name("_ZNSt3__113basic_istreamIcNS_11char_traitsIcEEE4readEPcl"),
            "_ZNSt3__113basic_istreamIcNS_11char_traitsIcEEE4readEPcx")

    def test_translate_keeps_identifiers(self):
        # 'm' e 'l' dentro de identificadores, comprimentos e substituicoes nao mudam
        n = "_ZNSt3__115basic_streambufIcNS_11char_traitsIcEEE9showmanycEv"
        self.assertEqual(translate_name(n), n)
        n = "_ZNSt3__16localeC1ERKS0_"
        self.assertEqual(translate_name(n), n)
        n = "_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE5flushEv"
        self.assertEqual(translate_name(n), n)

    def test_translate_template_args_and_literals(self):
        self.assertEqual(translate_name("_ZNSt3__16__sortIRNS_6__lessImlEEPmEEvT0_S5_T_"),
                         "_ZNSt3__16__sortIRNS_6__lessIyxEEPyEEvT0_S5_T_")
        self.assertEqual(translate_name("_Z3fooILm5EEvv"), "_Z3fooILy5EEvv")

    def test_no_signature_unchanged_except_mbstate(self):
        for n in ("_ZTVSt12length_error", "_ZTISt9exception", "_ZNSt3__14coutE",
                  "_ZSt7nothrow", "_ZNSt3__15ctypeIcE2idE", "__cxa_throw", "memcpy"):
            self.assertEqual(translate_name(n), n)
        self.assertEqual(translate_name("_ZNSt3__17codecvtIcc11__mbstate_tE2idE"),
                         "_ZNSt3__17codecvtIcc9_MbstatetE2idE")

    def test_thunk(self):
        n = "_ZTv0_n24_NSt3__113basic_istreamIcNS_11char_traitsIcEEED0Ev"
        self.assertEqual(translate_name(n), n)

    def test_failure_raises(self):
        for bad in ("_ZNSt3__1", "_ZN3foo", "_Z3fooQ", "_Z3fooPm3ba"):
            with self.assertRaises(TranslateError):
                translate_name(bad)


class ClassesTests(unittest.TestCase):
    def test_every_import_classified(self):
        have = {r[0] for r in load_tsv()}
        for lib in LIBS:
            with open(os.path.join(ROOT, "native", "imports", lib + ".txt"),
                      encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        self.assertIn(line, have, "%s: %s" % (lib, line))
        self.assertEqual(len(have), len(read_imports()))

    def test_tsv_columns_and_targets(self):
        exports = libcxx_exports()
        for name, cls, alvo in load_tsv():
            self.assertIn(cls, ("own", "libcxx"), name)
            if cls == "libcxx":
                self.assertIn(alvo, exports, name)
                self.assertEqual(alvo, translate_name(name))
            else:
                self.assertEqual(alvo, "", name)

    def test_tsv_matches_generator(self):
        gen = [(n, c, t) for n, c, t, _w in build_rows()]
        self.assertEqual(gen, load_tsv())

    def test_required_own(self):
        cls = {n: c for n, c, _t in load_tsv()}
        for n in ("_Unwind_Resume", "__gxx_personality_v0", "__chkstk_darwin",
                  "_tlv_atexit", "_Znwm", "_ZdlPv", "malloc", "rand", "printf",
                  "_ZNSt3__19to_stringEi",
                  "_ZNSt3__16__sortIRNS_6__lessIiiEEPiEEvT0_S5_T_",
                  "_ZNSt3__16chrono12system_clock3nowEv"):
            self.assertEqual(cls[n], "own", n)
        for n in ("_ZNSt3__115recursive_mutex4lockEv", "__dynamic_cast",
                  "_ZNSt3__16localeC1Ev", "_ZTVN10__cxxabiv117__class_type_infoE"):
            self.assertEqual(cls[n], "libcxx", n)

    def test_jassimp_rows(self):
        rows = {n: (c, t) for n, c, t in load_tsv()}
        self.assertIn("jassimp", LIBS)
        # unsigned long Apple (64 bits) -> unsigned long long do Windows; os dois nomes apontam para o mesmo alvo
        os_ = "_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEElsE"
        self.assertEqual(rows[os_ + "m"], ("libcxx", os_ + "y"))
        self.assertEqual(rows[os_ + "y"], ("libcxx", os_ + "y"))
        s = "St3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
        for n in ("_ZNK" + s + "4findEcm", "_ZNK" + s + "5rfindEcm", "_ZN" + s + "5eraseEmm"):
            self.assertEqual(rows[n][0], "own", n)
        for n in ("_ZSt17current_exceptionv", "_ZNSt13exception_ptrD1Ev", "_ZNSt13exception_ptraSERKS_",
                  "_DefaultRuneLocale", "__maskrune", "stat", "realpath$DARWIN_EXTSN", "snprintf"):
            self.assertEqual(rows[n][0], "own", n)


if __name__ == "__main__":
    unittest.main()
