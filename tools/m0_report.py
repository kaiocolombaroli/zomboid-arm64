"""Gera reports/m0-inventario.md e native/imports/*.txt (somente leitura das libs)."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from macho_inventory import parse_macho  # noqa: E402
from pe_exports import pe_exports  # noqa: E402

LIBS = os.path.join(ROOT, "game-libs", "macos-42.21")
GAME = os.environ.get("PZ_GAME_DIR", r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")
MAIN = {"lighting": "libLighting", "popman": "libPZPopMan", "pathfind": "libPZPathFind",
        "bullet": "libPZBullet", "clipper": "libPZClipper"}
PAIRS = {"libLighting": "Lighting64", "libPZPopMan": "PZPopMan64", "libPZPathFind": "PZPathFind64",
         "libPZBullet": "PZBullet64", "libPZClipper": "PZClipper64", "libRakNet": "RakNet64",
         "libZNetJNI": "ZNetJNI64", "libZNetNoSteam": "ZNetNoSteam64", "libsteam_api": "steam_api64",
         "libimgui-java64": "imgui-java64", "libjassimp": "jassimp64", "libbink64": "bink64",
         "libBink2Macx64": "bink2w64", "libfmod": "fmod", "libfmodstudio": "fmodstudio",
         "libfmodintegration": "fmodintegration64"}
COVER = {"FMOD": ["libfmod", "libfmodstudio", "libfmodintegration"], "ZNet": ["libZNetJNI", "libZNetNoSteam"],
         "RakNet": ["libRakNet"], "imgui": ["libimgui-java64"], "jassimp": ["libjassimp"],
         "bink": ["libbink64", "libBink2Macx64", "libBink2MacArm64"], "steam": ["libsteam_api"]}
VARIADIC = {"printf", "fprintf", "sprintf", "snprintf", "vsnprintf", "vprintf", "vfprintf", "vsprintf",
            "sscanf", "fscanf", "scanf", "open", "fcntl", "ioctl", "syslog", "execl", "execlp", "snprintf_l",
            "__snprintf_chk", "__sprintf_chk", "__vsnprintf_chk", "NSLog", "asprintf", "dprintf", "pthread_create"}


def family(n):
    if n.startswith(("_ZNSt3__1", "_ZNSt3__", "_ZSt")) or "St3__1" in n:
        if re.search(r"stream|ios|filebuf|locale|sentry", n):
            return "libc++ stream/locale"
        if "basic_string" in n or "__next_prime" in n or "__throw" in n or "__vector_base" in n:
            return "libc++ string/container"
        return "libc++ string/container"
    if n.startswith(("_ZTV", "_ZTI", "_ZTS")) and ("St3__1" in n or "St9" in n or "St" in n[4:6]):
        return "libc++ stream/locale"
    if n.startswith(("_Znw", "_Zna", "_Zdl", "_Zda")):
        return "libc++ string/container"
    if n.startswith(("__cxa", "__gxx", "_Unwind", "__dynamic_cast", "dyld_stub")) or "typeinfo" in n:
        return "excecao/unwind"
    if n.startswith(("pthread_", "sem_", "dispatch_")):
        return "pthread"
    if n.startswith(("_Z", "__Z")):
        return "libc++ string/container"
    return "libc"


def main():
    names = sorted(f for f in os.listdir(LIBS) if f.endswith(".dylib"))
    info = {}
    for f in names:
        with open(os.path.join(LIBS, f), "rb") as fh:
            info[f[:-6]] = parse_macho(fh.read())
    out = ["# M0 - Inventario das dylibs (42.21)", "",
           "Gerado por `python -I tools/m0_report.py`. Origem: depot 108602, `Project Zomboid.app/Contents/Java/`.", "",
           "## Tabela das libs", "",
           "| lib | arm64 | arm64e | dependencias | chained | TLV | eh_frame | unwind_info | imp | exp | exp x64 | so Mach-O | so x64 |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    detail = []
    for lib, r in info.items():
        pe = PAIRS.get(lib)
        x64 = None
        if pe:
            p = os.path.join(GAME, pe + ".dll")
            if os.path.exists(p):
                x64 = set(pe_exports(p))
        ex = set(r["exports"])
        only_m = sorted(ex - x64) if x64 is not None else None
        only_p = sorted(x64 - ex) if x64 is not None else None
        # compara so Java_* para a diferenca relevante
        jm = {e for e in ex if e.startswith("Java_")}
        jp = {e for e in x64 if e.startswith("Java_")} if x64 is not None else None
        jdiff = "n/a" if jp is None else "%d/%d" % (len(jm - jp), len(jp - jm))
        out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %d | %d | %s | %s | %s |" % (
            lib, r["arm64_present"], r["arm64e"], ", ".join(os.path.basename(d) for d in r["dylibs"]) or "-",
            r["chained_fixups"], r["tlv"], "__TEXT,__eh_frame" in r["sections"],
            "__TEXT,__unwind_info" in r["sections"], len(r["imports"]), len(ex),
            "n/a (%s)" % pe if x64 is None else len(x64), "n/a" if only_m is None else len(only_m),
            "n/a" if only_p is None else len(only_p)))
        detail.append((lib, pe, jdiff, only_m, only_p, jm, jp))
    out += ["", "`so Mach-O` = exports so na dylib; `so x64` = exports so na DLL x64 (todos os nomes, nao so JNI).",
            "", "### Diferenca de exports JNI (`Java_*`): so-dylib / so-DLL", ""]
    for lib, pe, jdiff, om, op, jm, jp in detail:
        out.append("- %s vs %s: %s (Java_* dylib=%d, x64=%s)" % (lib, pe, jdiff, len(jm), "n/a" if jp is None else len(jp)))
    out += ["", "### Nomes de exports que diferem (limitado a 10 por lado)", ""]
    for lib, pe, jdiff, om, op, jm, jp in detail:
        if om:
            out.append("- %s so na dylib: %s" % (lib, ", ".join(om[:10])))
        if op:
            out.append("- %s so na DLL: %s" % (lib, ", ".join(op[:10])))
    out += ["", "## Tipos de fixup, TLV e secoes de unwind", "",
            "Todas as libs: `chained_fixups` = ver tabela (False = LC_DYLD_INFO classico).", ""]
    for lib, r in info.items():
        out.append("- %s: secoes = %s" % (lib, " ".join(s.split(",")[1] for s in r["sections"] if s.startswith("__TEXT") or s.startswith("__DATA")) or "-"))
    out += ["", "## Importacoes por familia (5 libs principais)", ""]
    for key, lib in MAIN.items():
        imps = info[lib]["imports"]
        fams = {}
        for n in imps:
            fams.setdefault(family(n), []).append(n)
        out.append("### %s (%d importacoes)" % (lib, len(imps)))
        out.append("")
        for fam in ("libc", "libc++ string/container", "libc++ stream/locale", "excecao/unwind", "pthread"):
            lst = fams.pop(fam, [])
            out.append("- **%s** (%d): %s" % (fam, len(lst), ", ".join(lst)))
        rest = [n for v in fams.values() for n in v]
        out.append("- **outras** (%d): %s" % (len(rest), ", ".join(rest)))
        var = [n for n in imps if n in VARIADIC]
        out.append("- **variadicas** (%d): %s" % (len(var), ", ".join(var) or "nenhuma"))
        out.append("")
        with open(os.path.join(ROOT, "native", "imports", key + ".txt"), "w", newline="\n") as fh:
            fh.write("\n".join(imps) + "\n")
    out += ["## Cobertura das demais libs do depot", ""]
    for k, v in COVER.items():
        out.append("- %s: %s" % (k, ", ".join(x + (".dylib" if x in info else " (AUSENTE)") for x in v)))
    out += ["", "Todas as dylibs do depot: " + ", ".join(info), ""]
    with open(os.path.join(ROOT, "reports", "m0-inventario.md"), "w", newline="\n") as fh:
        fh.write("\n".join(out))
    return info


if __name__ == "__main__":
    main()
