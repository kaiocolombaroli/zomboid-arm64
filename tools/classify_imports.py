"""Classifica importacoes das dylibs macOS (own x libcxx) e traduz nomes Itanium.

A traducao opera na gramatica de mangling Itanium: somente tipos builtin em
posicao de tipo mudam (`m`->`y`, `l`->`x`, LP64 Apple -> LLP64 Windows) e
`__mbstate_t` vira o nome real da libc++ do llvm-mingw. Comprimentos,
identificadores e substituicoes nao mudam.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

LIBS = ("lighting", "popman", "pathfind", "bullet", "clipper", "jassimp", "fmodintegration")
BUILTIN_MAP = {"m": "y", "l": "x"}
BUILTINS = set("vwbcahstijlmxynofdegz")
MBSTATE_APPLE = "__mbstate_t"
MBSTATE_WIN = "_Mbstatet"
SUBST_STD = set("absiod")


class TranslateError(ValueError):
    pass


class _P:
    def __init__(self, s, tr):
        self.s, self.i, self.tr, self.out = s, 0, tr, []

    def err(self, msg):
        raise TranslateError("%s em %r pos %d" % (msg, self.s, self.i))

    def peek(self, k=0):
        j = self.i + k
        return self.s[j] if j < len(self.s) else ""

    def emit(self, text):
        self.out.append(text)

    def take(self, n=1):
        t = self.s[self.i:self.i + n]
        if len(t) < n:
            self.err("fim inesperado")
        self.i += n
        self.out.append(t)
        return t

    def expect(self, ch):
        if self.peek() != ch:
            self.err("esperado %r" % ch)
        self.take()

    # --- pecas ------------------------------------------------------------
    def source_name(self):
        j = self.i
        while self.peek().isdigit():
            self.i += 1
        if j == self.i:
            self.err("source-name esperado")
        n = int(self.s[j:self.i])
        ident = self.s[self.i:self.i + n]
        if len(ident) != n:
            self.err("source-name truncado")
        self.i += n
        if ident == MBSTATE_APPLE:
            self.emit("%d%s" % (len(MBSTATE_WIN), MBSTATE_WIN))
        else:
            self.emit(self.s[j:self.i])

    def seq_id_subst(self):
        self.take()  # S (nao 'St')
        if self.peek() in SUBST_STD and self.peek():
            self.take()
            return
        while self.peek() and self.peek() != "_":
            if not (self.peek().isdigit() or self.peek().isupper()):
                self.err("substituicao invalida")
            self.take()
        self.expect("_")

    def template_param(self):
        self.take()  # T
        while self.peek() and self.peek() != "_":
            if not self.peek().isdigit():
                self.err("template-param invalido")
            self.take()
        self.expect("_")

    def targs(self):
        self.expect("I")
        while self.peek() != "E":
            if not self.peek():
                self.err("template-args sem fim")
            self.targ()
        self.take()

    def targ(self):
        c = self.peek()
        if c == "L":
            self.take()
            if self.peek() == "Z" or self.s.startswith("_Z", self.i):
                if self.peek() == "_":
                    self.take()
                self.take()  # Z
                self.encoding()
                self.expect("E")
                return
            self.type_()
            while self.peek() != "E":
                if not self.peek():
                    self.err("literal sem fim")
                self.take()
            self.take()
        elif c == "J":
            self.take()
            while self.peek() != "E":
                if not self.peek():
                    self.err("pack sem fim")
                self.targ()
            self.take()
        elif c == "X":
            self.err("expressao em template-arg nao suportada")
        else:
            self.type_()

    def unqualified(self):
        c = self.peek()
        if c == "L":
            self.take()
            c = self.peek()
        if c.isdigit():
            self.source_name()
        elif c == "C" or (c == "D" and self.peek(1) in "012345" and self.peek(1)):
            self.take(2)
        elif c.islower():
            self.operator()
        else:
            self.err("nome nao suportado")
        while self.peek() == "B":
            self.take()
            self.source_name()

    def operator(self):
        op = self.s[self.i:self.i + 2]
        if op == "cv":
            self.take(2)
            self.type_()
        elif op == "li":
            self.take(2)
            self.source_name()
        elif len(op) == 2 and op[0] == "v" and op[1].isdigit():
            self.take(2)
            self.source_name()
        elif len(op) == 2 and op.isalpha():
            self.take(2)
        else:
            self.err("operador invalido")

    def name(self):
        c = self.peek()
        if c == "N":
            self.nested()
        elif c == "Z":
            self.take()
            self.encoding()
            self.expect("E")
            if self.peek() == "s":
                self.take()
            else:
                self.name()
            if self.peek() == "_":
                self.take()
                while self.peek().isdigit() or self.peek() == "_":
                    self.take()
        elif c == "S":
            if self.peek(1) == "t":
                self.take(2)
                self.unqualified()
            else:
                self.seq_id_subst()
            if self.peek() == "I":
                self.targs()
        else:
            self.unqualified()
            if self.peek() == "I":
                self.targs()

    def nested(self):
        self.expect("N")
        while self.peek() and self.peek() in "rVK":
            self.take()
        if self.peek() and self.peek() in "RO":
            self.take()
        while True:
            c = self.peek()
            if not c:
                self.err("nested sem E")
            if c == "E":
                self.take()
                return
            if c == "S":
                if self.peek(1) == "t":
                    self.take(2)
                else:
                    self.seq_id_subst()
            elif c == "T":
                self.template_param()
            elif c == "I":
                self.targs()
            elif c == "D" and self.peek(1) in ("t", "T"):
                self.err("decltype nao suportado")
            elif c == "U":
                self.err("nome unnamed/lambda nao suportado")
            else:
                self.unqualified()

    # --- tipos ------------------------------------------------------------
    def type_(self):
        c = self.peek()
        if not c:
            self.err("tipo esperado")
        if c in "rVKPROCG":
            self.take()
            return self.type_()
        if c == "U":
            self.take()
            self.source_name()
            return self.type_()
        if c in BUILTINS:
            self.i += 1
            self.emit(BUILTIN_MAP.get(c, c) if self.tr else c)
            return
        if c == "u":
            self.take()
            return self.source_name()
        if c == "D":
            d = self.peek(1)
            if d == "p":
                self.take(2)
                return self.type_()
            if d and d in "nacsiufdeh":
                self.take(2)
                return
            if d == "F":
                self.take(2)
                while self.peek().isdigit():
                    self.take()
                self.expect("_")
                return
            if d == "v":
                self.take(2)
                while self.peek().isdigit():
                    self.take()
                self.expect("_")
                return self.type_()
            self.err("tipo D? nao suportado")
        if c == "F":
            self.take()
            if self.peek() == "Y":
                self.take()
            while self.peek() != "E":
                if not self.peek():
                    self.err("function type sem E")
                if self.peek() in ("R", "O") and self.peek(1) == "E":
                    self.take()
                    continue
                self.type_()
            self.take()
            return
        if c == "A":
            self.take()
            while self.peek().isdigit():
                self.take()
            self.expect("_")
            return self.type_()
        if c == "M":
            self.take()
            self.type_()
            return self.type_()
        if c == "T":
            self.template_param()
            if self.peek() == "I":
                self.targs()
            return
        if c == "S":
            if self.peek(1) == "t":
                self.take(2)
                self.unqualified()
            else:
                self.seq_id_subst()
            if self.peek() == "I":
                self.targs()
            return
        if c.isdigit() or c in "NZ":
            self.name()
            return
        self.err("tipo nao suportado %r" % c)

    # --- encoding ---------------------------------------------------------
    def encoding(self):
        c = self.peek()
        if c == "T":
            k = self.peek(1)
            if k and k in "VISTC":
                self.take(2)
                self.type_()
                return
            if k in ("v", "h"):
                self.take(2)
                self.offset()
                if k == "v":
                    self.offset()
                return self.encoding()
            self.err("nome especial T%s nao suportado" % k)
        if c == "G" and self.peek(1) == "V":
            self.take(2)
            return self.name()
        self.name()
        while self.peek() and self.peek() != "E":
            self.type_()

    def offset(self):
        if self.peek() == "n":
            self.take()
        while self.peek().isdigit():
            self.take()
        self.expect("_")


def translate_name(apple: str) -> str:
    """Traduz um nome Itanium Apple (LP64) para o equivalente Windows (LLP64).

    Levanta TranslateError se o mangling nao puder ser interpretado.
    Nomes sem assinatura de parametros (vtables, typeinfos, dados) ficam
    iguais, exceto `__mbstate_t`.
    """
    if not apple.startswith("_Z"):
        return apple
    sig = not apple.startswith(("_ZTV", "_ZTI", "_ZTS", "_ZTT", "_ZGV", "_ZZ"))
    p = _P(apple, tr=sig)
    p.i = 2
    p.emit("_Z")
    p.encoding()
    if p.i != len(apple):
        p.err("lixo apos o encoding")
    return "".join(p.out)


# --- classificacao ------------------------------------------------------------

_OWN_RULES = (
    (r"^_ZNK?St3__112basic_string", "basic_string tem layout Apple proprio"),
    (r"^_ZSt17current_exceptionv|^_ZNSt13exception_ptr", "exception_ptr/current_exception ligados ao runtime de excecoes proprio (dw_eh.c)"),
    (r"^_ZNSt3__1plIcNS_11char_traitsIcEENS_9allocatorIcEEE", "operator+ de string"),
    (r"^_ZNSt3__19to_string", "to_string retorna basic_string Apple"),
    (r"^_ZNSt3__113random_device", "random_device (BCryptGenRandom)"),
    (r"^_ZNSt13runtime_errorC[12]ERKNSt3__112basic_string",
     "runtime_error(string) recebe basic_string Apple"),
    (r"^_ZNSt3__16__sort", "__sort e template instanciado para a dylib Apple"),
    (r"^_ZNSt3__16chrono12system_clock3now", "system_clock::now (clock proprio)"),
    (r"^_ZNSt3__15mutex(4lockEv|6unlockEv|D[012]Ev)$",
     "std::mutex Apple e um pthread_mutex_t de 64 bytes com assinatura inline (dw_pthread.c)"),
    (r"^_Zn[aw]m|^_Zd[al]Pv", "new/delete"),
    (r"^_ZSt9terminatev$", "terminate do runtime de excecoes proprio"),
    (r"^_ZGV|^_ZZ", "simbolo local/weak da propria dylib (guard/static local)"),
)
_LIBCXX_C = {"__dynamic_cast"}


def classify(apple: str):
    """Retorna (classe, motivo). Nao consulta a libc++ real."""
    if not apple.startswith("_Z"):
        if apple in _LIBCXX_C:
            return "libcxx", "RTTI via libc++abi"
        return "own", "libc/matematica/alocacao/runtime Apple"
    for rx, why in _OWN_RULES:
        if re.search(rx, apple):
            return "own", why
    return "libcxx", "classe C++ da libc++"


def read_imports():
    names = {}
    for lib in LIBS:
        path = os.path.join(ROOT, "native", "imports", lib + ".txt")
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    names.setdefault(line, set()).add(lib)
    return names


def libcxx_exports():
    from pe_exports import pe_exports
    cands = glob.glob(os.path.join(ROOT, "tools", "toolchain", "llvm-mingw", "*",
                                   "aarch64-w64-mingw32", "bin", "libc++.dll"))
    if not cands:
        raise FileNotFoundError("libc++.dll nao encontrada")
    return set(pe_exports(cands[0]))


def build_rows(exports=None):
    """Lista de (nome_apple, classe, alvo, motivo), ordenada."""
    exports = libcxx_exports() if exports is None else exports
    rows = []
    for name in sorted(read_imports()):
        cls, why = classify(name)
        target = ""
        if cls == "libcxx":
            target = translate_name(name)
            if target not in exports:
                missing, target, cls = target, "", "own"
                if name.startswith("_ZTI"):
                    why = ("sem equivalente: typeinfo nao exportado por libc++.dll "
                           "(existe so na libc++.a); alvo %s ausente" % missing)
                elif name.startswith("_Z"):
                    why = ("sem equivalente: funcao inline na libc++ do llvm-mingw "
                           "(diferenca de ABI inline); alvo %s ausente" % missing)
                else:
                    why = "sem equivalente: %s ausente" % missing
        rows.append((name, cls, target, why))
    return rows


def write_tsv(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("nome_apple\tclasse\talvo\n")
        for n, c, t, _w in rows:
            f.write("%s\t%s\t%s\n" % (n, c, t))


if __name__ == "__main__":
    rows = build_rows()
    out = os.path.join(ROOT, "native", "imports", "classes.tsv")
    write_tsv(out, rows)
    cnt = {}
    for _n, c, _t, _w in rows:
        cnt[c] = cnt.get(c, 0) + 1
    print(out, len(rows), cnt)
    for n, c, t, w in rows:
        if w.startswith("sem equivalente"):
            print("FALLBACK", n, "|", w)
