#!/usr/bin/env python3
"""Gera native/fmodshim/fmodshim_gen.inc: a ponte entre libfmodintegration.dylib (Apple) e as DLLs FMOD ARM64.

  * `FMOD_*` (API C): a dylib e a DLL usam as mesmas assinaturas e a mesma convencao (AAPCS64, nenhum com mais de
    8 argumentos inteiros/float, nenhum variadico, nenhuma estrutura por valor > 16 bytes): ponteiro direto,
    resolvido por GetProcAddress em JNI_OnLoad. A tabela lista os nomes que a dylib importa.
  * `FMOD::Studio::*` (API C++): a DLL do Windows nao exporta esses simbolos Itanium; cada metodo vira uma funcao
    shim que recebe `this` (= o handle da API C) e chama a funcao C equivalente. A assinatura C++ vem de
    fmod_studio.hpp (`Classe::metodo`), o nome C vem de `FMOD_Studio_<Classe>_<Metodo>` e e conferido contra
    fmod_studio.h. `bool` por valor chega como o byte baixo de um registrador de 32 bits (Apple estende o byte); `bool*`
    de saida e 1 byte (C++), enquanto FMOD_BOOL e int.  Cada simbolo Itanium importado e decodificado e seus tipos
    sao conferidos contra a declaracao do .hpp; qualquer divergencia aborta a geracao.

Uso: python -I tools/gen_fmod_shim.py [--fmod-api DIR] [--dylib D] [--out ARQ] [--table ARQ.md] [--check]
`--check` so confere se o arquivo gerado esta atualizado (codigo 1 se nao)."""
import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

FMOD_API = r"C:\Program Files (x86)\FMOD SoundSystem\FMOD Studio API Windows\api"
DEFAULT_OUT = os.path.join(ROOT, "native", "fmodshim", "fmodshim_gen.inc")
DEFAULT_DYLIB = os.path.join(ROOT, "game-libs", "macos-42.21", "libfmodintegration.dylib")


class GenError(Exception):
    pass


# ---------------------------------------------------------------------------------------------------------
# Cabecalhos
def strip_comments(t):
    t = re.sub(r"/\*.*?\*/", " ", t, flags=re.S)
    return re.sub(r"//[^\n]*", " ", t)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return strip_comments(f.read())


def typedef_kinds(api):
    """nome de tipo FMOD -> classe ABI do mangling: 'i','j','f','P' (ponteiro/funcao) ou 'N' (enum/struct nomeado)."""
    kinds = {}
    for rel in ("core/inc/fmod_common.h", "studio/inc/fmod_studio_common.h"):
        t = read(os.path.join(api, rel))
        for m in re.finditer(r"typedef\s+(unsigned int|int|float)\s+(\w+)\s*;", t):
            kinds[m.group(2)] = {"unsigned int": "j", "int": "i", "float": "f"}[m.group(1)]
        for m in re.finditer(r"typedef\s+[\w\s\*]+?\(\s*F_CALL\s*\*\s*(\w+)\s*\)", t):
            kinds[m.group(1)] = "P"
        for m in re.finditer(r"\}\s*(\w+)\s*;", t):          # fim de typedef enum/struct
            kinds.setdefault(m.group(1), "N")
    return kinds


def struct_names(api):
    """Nomes de typedef struct dos cabecalhos FMOD (passar por valor exige conferir a convencao)."""
    names = set()
    for rel in ("core/inc/fmod_common.h", "core/inc/fmod_dsp.h", "studio/inc/fmod_studio_common.h"):
        t = read(os.path.join(api, rel))
        for m in re.finditer(r"typedef\s+struct\s+\w*\s*\{", t):
            depth, i = 0, m.end() - 1
            while True:
                if t[i] == "{":
                    depth += 1
                elif t[i] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            mm = re.match(r"\s*(\w+)\s*;", t[i + 1:])
            if mm:
                names.add(mm.group(1))
    return names


def parse_hpp(api):
    """{ 'System': {'create': Method, ...}, ... } do namespace FMOD::Studio."""
    t = read(os.path.join(api, "studio/inc/fmod_studio.hpp"))
    out = {}
    for cm in re.finditer(r"\bclass\s+(\w+)\s*\{(.*?)\n    \};", t, flags=re.S):
        cls, body = cm.group(1), cm.group(2)
        for m in re.finditer(r"^\s*(static\s+)?([\w:]+)\s+F_API\s+(\w+)\s*\(([^)]*)\)\s*(const)?\s*;", body, flags=re.M):
            static, ret, name, params, const = m.groups()
            plist = []
            for p in [x.strip() for x in params.split(",")] if params.strip() else []:
                p = p.split("=")[0].strip()
                mm = re.match(r"(.*?)(\w+)$", p)
                typ = re.sub(r"\s+", " ", mm.group(1).strip()).replace(" *", " *")
                plist.append((typ.replace("* ", "*").replace(" *", " *"), mm.group(2)))
            out.setdefault(cls, {})[name] = {"static": bool(static), "ret": ret, "params": plist, "const": bool(const)}
    return out


def parse_c_protos(api):
    t = read(os.path.join(api, "studio/inc/fmod_studio.h")) + read(os.path.join(api, "core/inc/fmod.h"))
    protos = {}
    for m in re.finditer(r"F_API\s+(FMOD_\w+)\s*\(([^)]*)\)\s*;", t):
        protos[m.group(1)] = [re.sub(r"\s+", " ", p.strip()) for p in m.group(2).split(",")] if m.group(2).strip() != "void" else []
    return protos


# ---------------------------------------------------------------------------------------------------------
# Itanium (so o que as 61 importacoes usam)
def demangle_method(sym):
    """'_ZNK4FMOD6Studio13EventInstance11getUserDataEPPv' -> (['FMOD','Studio','EventInstance','getUserData'], kinds)."""
    s = sym[3:]
    const = s.startswith("K")
    if const:
        s = s[1:]
    parts, i = [], 0
    while i < len(s) and s[i].isdigit():
        j = i
        while s[j].isdigit():
            j += 1
        n = int(s[i:j])
        parts.append(s[j:j + n])
        i = j + n
    if i >= len(s) or s[i] != "E":
        raise GenError("simbolo Itanium inesperado: " + sym)
    return parts, _kinds(s[i + 1:], sym), const


def _kinds(s, sym):
    pos = [0]

    def one():
        c = s[pos[0]]
        if c in "PR":
            pos[0] += 1
            one()
            return "P"
        if c == "K":
            pos[0] += 1
            return one()
        if c in "ijfbcv":
            pos[0] += 1
            return c
        if c.isdigit():
            j = pos[0]
            while s[j].isdigit():
                j += 1
            pos[0] = j + int(s[pos[0]:j])
            return "N"
        if c == "N":
            depth = 0
            while True:
                ch = s[pos[0]]
                if ch == "N":
                    depth += 1
                elif ch == "E":
                    depth -= 1
                    if depth == 0:
                        pos[0] += 1
                        return "N"
                elif ch.isdigit():
                    j = pos[0]
                    while s[j].isdigit():
                        j += 1
                    pos[0] = j + int(s[pos[0]:j])
                    continue
                pos[0] += 1
        if c == "S":
            pos[0] = s.index("_", pos[0]) + 1
            return "?"          # substituicao (tipo ja visto): serve a qualquer classe
        if c == "F":
            pos[0] += 1
            one()
            while s[pos[0]] != "E":
                one()
            pos[0] += 1
            return "F"
        raise GenError("tipo Itanium nao tratado em %s: %r" % (sym, s[pos[0]:]))

    out = []
    while pos[0] < len(s):
        out.append(one())
    return [] if out == ["v"] else out


def hpp_kind(typ, kinds):
    t = typ.replace("const ", "").strip()
    if t.endswith("*"):
        return "P"
    if t in ("int", "unsigned int", "float", "bool"):
        return {"int": "i", "unsigned int": "j", "float": "f", "bool": "b"}[t]
    k = kinds.get(t)
    if k is None:
        raise GenError("tipo desconhecido no .hpp: " + typ)
    return "P" if k == "P" else k


# ---------------------------------------------------------------------------------------------------------
# Tipos C do shim (ABI-equivalentes; nenhum cabecalho FMOD e necessario para compilar)
def c_param(typ, kinds):
    """(tipo declarado no shim, conversao ao chamar a API C, prologo/epilogo)."""
    t = typ.replace("const ", "").strip()
    if t == "bool":
        return "unsigned int", "(%s & 0xFFu) != 0", None
    if t == "bool *":
        return "uint8_t *", None, "boolout"
    if t == "char *":
        return "char *", "%s", None
    if t.endswith("*"):
        return "void *", "%s", None
    if t == "FMOD_STUDIO_PARAMETER_ID":
        return "pz_param_id", "%s", None
    k = kinds.get(t)
    if k == "j":
        return "unsigned int", "%s", None
    if k == "i":
        return "int", "%s", None
    if k == "f":
        return "float", "%s", None
    if k == "P":
        return "void *", "%s", None
    if t in ("int", "unsigned int", "float"):
        return t, "%s", None
    if k == "N":      # enum por valor: int de 4 bytes nos dois lados
        return "int", "%s", None
    raise GenError("sem mapeamento C para " + typ)


def c_name(cls, method):
    return "FMOD_Studio_%s_%s" % (cls, method[0].upper() + method[1:])


def generate(api, dylib_path):
    from macho_inventory import parse_macho
    with open(dylib_path, "rb") as f:
        info = parse_macho(f.read())
    imports = sorted(info["imports"])
    direct = [n for n in imports if n.startswith("FMOD_")]
    cxx = [n for n in imports if "4FMOD" in n]

    kinds = typedef_kinds(api)
    hpp = parse_hpp(api)
    protos = parse_c_protos(api)

    # Convencao: nenhuma funcao C direta com pilha, variadica ou estrutura por valor grande
    risky = []
    structs = struct_names(api)
    for n in direct:
        if n not in protos:
            raise GenError("prototipo C nao encontrado nos cabecalhos: " + n)
        ps = protos[n]
        if "..." in ps:
            risky.append((n, "variadica"))
        ints = flts = 0
        for p in ps:
            ptr = "*" in p
            typ = p.rsplit(" ", 1)[0].replace("const ", "").strip()
            if not ptr and typ in structs:
                risky.append((n, "estrutura por valor: " + p))
                continue
            wide = ptr or "long long" in typ or typ in ("FMOD_PORT_INDEX", "FMOD_THREAD_AFFINITY")
            if not ptr and typ in ("float", "double"):
                flts += 1
                if flts > 8:
                    risky.append((n, "float na pilha: " + p))
            else:
                ints += 1
                # Argumento de pilha: a Apple empacota por tamanho natural, a AAPCS usa 8 bytes por slot.
                # Ponteiros e inteiros de 64 bits tem o mesmo layout nos dois; menores nao.
                if ints > 8 and not wide:
                    risky.append((n, "argumento de pilha com menos de 8 bytes: " + p))
    if risky:
        raise GenError("FMOD_* direto com convencao nao compativel: %r" % risky)

    shims = []
    for sym in cxx:
        parts, got, const = demangle_method(sym)
        if parts[:2] != ["FMOD", "Studio"] or len(parts) != 4:
            raise GenError("simbolo C++ fora de FMOD::Studio: " + sym)
        cls, name = parts[2], parts[3]
        m = hpp.get(cls, {}).get(name)
        if not m:
            raise GenError("metodo ausente no fmod_studio.hpp: %s::%s" % (cls, name))
        if m["ret"] != "FMOD_RESULT":
            raise GenError("retorno nao FMOD_RESULT em %s::%s" % (cls, name))
        want = [hpp_kind(t, kinds) for t, _ in m["params"]]
        if len(want) != len(got) or any(g != "?" and g != w for w, g in zip(want, got)):
            raise GenError("tipos de %s::%s divergem do simbolo %s: .hpp=%s simbolo=%s" % (cls, name, sym, want, got))
        if const != m["const"]:
            raise GenError("const diverge em %s::%s" % (cls, name))
        cn = c_name(cls, name)
        if cn not in protos:
            raise GenError("funcao C %s nao existe nos cabecalhos" % cn)
        cp = protos[cn]
        expect_n = len(m["params"]) + (0 if m["static"] else 1)
        if len(cp) != expect_n:
            raise GenError("%s tem %d args, esperado %d" % (cn, len(cp), expect_n))
        shims.append({"sym": sym, "cls": cls, "name": name, "cname": cn, "m": m, "cproto": cp})

    # --- saida ---
    L = ["/* gerado por tools/gen_fmod_shim.py - nao editar. Tabela revisada: ver reports/m5-fmod.md. */",
         "/* Incluido uma unica vez por native/fmodshim/fmodshim.c. */", ""]
    L.append("static const char* const g_fmod_direct[] = {")
    L += ['    "%s",' % n for n in direct]
    L += ["};", "#define FMOD_DIRECT_COUNT %d" % len(direct), ""]

    # funcoes C usadas pelas shims
    targets = sorted({s["cname"] for s in shims})
    for t in targets:
        L.append("static void* p_%s;" % t)
    L.append("static const struct { const char* name; void** slot; } g_fmod_targets[] = {")
    L += ['    { "%s", &p_%s },' % (t, t) for t in targets]
    L += ["};", "#define FMOD_TARGET_COUNT %d" % len(targets), ""]

    L += ["typedef struct { unsigned int data1, data2; } pz_param_id;", ""]

    rows = []
    for i, s in enumerate(shims):
        m, kinds_ = s["m"], kinds
        decl, call, pre, post = [], [], [], []
        if not m["static"]:
            decl.append("void *self")
            call.append("self")
        ctypes = ["void *"] if not m["static"] else []
        for j, (typ, pname) in enumerate(m["params"]):
            ct, conv, special = c_param(typ, kinds_)
            decl.append("%s %s" % (ct, pname))
            ctypes.append(ct if special != "boolout" else "int *")
            if special == "boolout":
                pre.append("int tmp_%s = 0;" % pname)
                call.append("%s ? &tmp_%s : NULL" % (pname, pname))
                post.append("if (rc == 0 && %s) *%s = tmp_%s != 0;" % (pname, pname, pname))
            elif conv is None:
                call.append(pname)
            else:
                call.append(conv % pname if "%s" in conv else conv)
        fn = "pz_shim_%03d_%s_%s" % (i, s["cls"], s["name"])
        proto = "int (*)(%s)" % ", ".join(ctypes)
        L.append("/* %s::%s(%s)%s -> %s */" % (s["cls"], s["name"], ", ".join("%s %s" % p for p in m["params"]),
                                               " const" if m["const"] else "", s["cname"]))
        L.append("static int %s(%s) {" % (fn, ", ".join(decl) if decl else "void"))
        for p in pre:
            L.append("    " + p)
        L.append("    int rc = ((%s)p_%s)(%s);" % (proto, s["cname"], ", ".join(call)))
        for p in post:
            L.append("    " + p)
        L += ["    return rc;", "}", ""]
        rows.append((s["sym"], fn, s["cls"], s["name"], m, s["cname"]))

    L.append("static const struct { const char* name; void* fn; } g_fmod_shims[] = {")
    L += ['    { "%s", (void*)%s },' % (r[0], r[1]) for r in rows]
    L += ["};", "#define FMOD_SHIM_COUNT %d" % len(rows), ""]
    return "\n".join(L), rows, direct


def table_md(rows):
    out = ["| # | Metodo C++ (fmod_studio.hpp) | Funcao C |", "|---|---|---|"]
    for i, (sym, fn, cls, name, m, cn) in enumerate(rows):
        sig = "%s%s::%s(%s)%s" % ("static " if m["static"] else "", cls, name,
                                  ", ".join("%s %s" % p for p in m["params"]), " const" if m["const"] else "")
        out.append("| %d | `%s` | `%s` |" % (i + 1, sig, cn))
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--fmod-api", default=FMOD_API)
    ap.add_argument("--dylib", default=DEFAULT_DYLIB)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--table", default=None)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    if a.check and not os.path.isdir(a.fmod_api):
        print("gen_fmod_shim: cabecalhos FMOD ausentes (%s): conferencia ignorada" % a.fmod_api)
        return 77
    try:
        text, rows, direct = generate(a.fmod_api, a.dylib)
    except GenError as e:
        print("gen_fmod_shim: erro: %s" % e, file=sys.stderr)
        return 1
    if a.check:
        try:
            with open(a.out, encoding="utf-8", newline="") as f:
                cur = f.read()
        except OSError:
            cur = None
        if cur != text:
            print("gen_fmod_shim: %s desatualizado" % a.out, file=sys.stderr)
            return 1
        print("gen_fmod_shim: atualizado (%d shims, %d diretas)" % (len(rows), len(direct)))
        return 0
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    if a.table:
        with open(a.table, "w", encoding="utf-8", newline="\n") as f:
            f.write(table_md(rows))
    print("gen_fmod_shim: %d shims C++, %d FMOD_* diretas -> %s" % (len(rows), len(direct), a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
