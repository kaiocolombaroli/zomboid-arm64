#!/usr/bin/env python3
"""Conta metodos ACC_NATIVE por classe e acha cargas de libs nativas (leitura apenas).
Uso: python -I scan_natives.py <projectzomboid.jar> <saida.txt>"""
import sys, zipfile, struct, collections, re

KEYS = ["loadLibrary", "System.load", "os.arch", "os.name", "UseNativeCode", "Lighting64", "PZPopMan64",
        "PZPathFind64", "PZBullet64", "PZClipper64", "fmodintegration64", "RakNet64", "ZNetJNI64",
        "ZNetNoSteam64", "imgui-java64", "jassimp64", "bink", "steam_api64", "fmodstudio", "aarch64", "arm64"]

def parse(data):
    """retorna (this_name, [strings utf8 do pool], [(nome_metodo, flags)])"""
    p = 8
    n = struct.unpack_from(">H", data, p)[0]; p += 2
    utf = {}; i = 1
    while i < n:
        t = data[p]
        if t == 1:
            l = struct.unpack_from(">H", data, p + 1)[0]
            utf[i] = data[p + 3:p + 3 + l].decode("utf-8", "replace"); p += 3 + l
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): p += 5
        elif t in (5, 6): p += 9; i += 1
        elif t in (7, 8, 16, 19, 20): p += 3
        elif t == 15: p += 4
        else: raise ValueError("tag %d" % t)
        i += 1
    p += 6  # access, this, super
    ic = struct.unpack_from(">H", data, p)[0]; p += 2 + 2 * ic
    def members():
        nonlocal p
        out = []
        c = struct.unpack_from(">H", data, p)[0]; p += 2
        for _ in range(c):
            fl, ni, di, ac = struct.unpack_from(">HHHH", data, p); p += 8
            out.append((utf.get(ni, "?"), fl))
            for _ in range(ac):
                ln = struct.unpack_from(">I", data, p + 2)[0]; p += 6 + ln
        return out
    members()  # fields
    meths = members()
    return list(utf.values()), meths

def main():
    jar, outp = sys.argv[1], sys.argv[2]
    z = zipfile.ZipFile(jar)
    per = {}; hits = collections.defaultdict(list); errs = 0
    total_cls = 0
    for nm in z.namelist():
        if not nm.endswith(".class") or nm.startswith("META-INF/"): continue
        total_cls += 1
        try: strs, meths = parse(z.read(nm))
        except Exception: errs += 1; continue
        nat = [m for m, f in meths if f & 0x0100]
        if nat: per[nm] = nat
        top = nm.split("/")[0]
        if top in ("zombie", "fmod", "se", "generation", "astar", "N3D", "pl", "de", "jassimp") or nm.startswith(("imgui/", "org/lwjgl/system/Library", "org/lwjgl/system/Platform", "org/lwjgl/Version", "com/sun/jna/Native", "com/sun/jna/Platform", "org/sqlite/SQLiteJDBCLoader", "org/sqlite/util/OSInfo", "oshi/PlatformEnum", "oshi/SystemInfo")):
            for s in strs:
                for k in KEYS:
                    if k in s:
                        hits[nm].append(s if len(s) < 120 else s[:120] + "...")
                        break
    with open(outp, "w", encoding="utf-8") as f:
        f.write("# classes analisadas: %d (erros de parse: %d)\n" % (total_cls, errs))
        f.write("# classes com metodos native: %d; total de metodos native: %d\n\n" % (len(per), sum(len(v) for v in per.values())))
        bypkg = collections.Counter()
        for k, v in per.items(): bypkg["/".join(k.split("/")[:3]) if k.startswith(("org/lwjgl","com/sun","zombie")) else "/".join(k.split("/")[:2])] += len(v)
        f.write("## Metodos native por pacote\n")
        for k, c in bypkg.most_common(): f.write("%5d  %s\n" % (c, k))
        f.write("\n## Metodos native por classe (zombie/ e demais fora de lwjgl/jna/sqlite/oshi/imgui detalhados)\n")
        for k in sorted(per):
            if k.startswith(("org/lwjgl/", "com/sun/jna/", "org/sqlite/", "oshi/", "imgui/")): continue
            f.write("%3d  %s  %s\n" % (len(per[k]), k, ",".join(sorted(set(per[k]))[:6])))
        f.write("\n## Classes native de bibliotecas (contagem)\n")
        for k in sorted(per):
            if k.startswith(("org/lwjgl/", "com/sun/jna/", "org/sqlite/", "oshi/", "imgui/")): f.write("%3d  %s\n" % (len(per[k]), k))
        f.write("\n## Strings-chave (carga de libs / os.arch) nas classes do jogo\n")
        for k in sorted(hits):
            f.write("%s\n" % k)
            for s in sorted(set(hits[k])): f.write("    %r\n" % s)
if __name__ == "__main__": main()
