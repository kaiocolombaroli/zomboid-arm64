"""Comparacao por banda para LH_TRANSMISSION=1 (complementa, nao substitui, compare.py).
Uso: python compare_band.py x64_run1.txt,x64_run2.txt,... arm64_run1.txt,... (listas separadas por virgula)
 ou: python compare_band.py --glob "reports/transmission/x64-run*.txt" "reports/transmission/arm64-run*.txt"
Por token numerico: banda [min,max] das execucoes de cada arquitetura. Passa se |mediana ARM64 - mediana x64|:
  - cor empacotada ARGB (inteiro com |v| >= 65536): <= 4 por canal (de 255);
  - linhas 'info' (cor RGB em float 0..1): <= 4/255 absoluto (e cor, nao dado relativo);
  - demais numeros: diferenca relativa <= 2% (piso absoluto 1e-6 para valores ~0).
Tokens nao numericos (true/false, rotulos): devem ser iguais em todas as execucoes.
"""
import glob
import re
import statistics
import sys

COLOR_TOL = 4
REL = 0.02
ABS = 1e-6
NUM = re.compile(r'^[+-]?\d+(\.\d+)?(e[+-]?\d+)?$', re.I)


def load(paths):
    runs = []
    for p in paths:
        runs.append([l.split() for l in open(p, encoding='utf-8').read().splitlines()])
    n = len(runs[0])
    for r in runs:
        if len(r) != n or any(len(a) != len(b) for a, b in zip(r, runs[0])):
            sys.exit(f'FALHA: formato difere entre execucoes ({paths})')
    return runs


def is_color(s):
    return re.fullmatch(r'[+-]?\d+', s) is not None and abs(int(s)) >= 65536


def channels(v):
    u = int(v) & 0xFFFFFFFF
    return [(u >> 24) & 255, (u >> 16) & 255, (u >> 8) & 255, u & 255]


def split_kv(t):
    if '=' in t:
        k, _, v = t.partition('=')
        return k, v
    return '', t


def main():
    args = sys.argv[1:]
    if args and args[0] == '--glob':
        xs, as_ = sorted(glob.glob(args[1])), sorted(glob.glob(args[2]))
    else:
        xs, as_ = args[0].split(','), args[1].split(',')
    X, A = load(xs), load(as_)
    if len(X[0]) != len(A[0]):
        print(f'FALHA: linhas {len(X[0])} vs {len(A[0])}')
        return 1
    bad = 0
    own_var_lines = set()
    own_var_x = own_var_a = 0
    worst = (0.0, None)  # (divergencia normalizada = erro/tolerancia, descricao)
    worst_color = 0
    worst_rel = 0.0
    first = None
    for li in range(len(X[0])):
        ntok = len(X[0][li])
        for ti in range(ntok):
            tx = [r[li][ti] for r in X]
            ta = [r[li][ti] for r in A]
            keys = {split_kv(t)[0] for t in tx + ta}
            if len(keys) != 1:
                bad += 1; first = first or (li, ti, 'chave difere'); continue
            vx = [split_kv(t)[1] for t in tx]
            va = [split_kv(t)[1] for t in ta]
            if not all(NUM.match(v) for v in vx + va):
                if len(set(vx + va)) != 1:
                    bad += 1; first = first or (li, ti, f'{set(vx + va)}')
                continue
            if all(is_color(v) for v in vx + va):
                cx = [channels(v) for v in vx]; ca = [channels(v) for v in va]
                medx = [statistics.median(c[k] for c in cx) for k in range(4)]
                meda = [statistics.median(c[k] for c in ca) for k in range(4)]
                spx = max(max(c[k] for c in cx) - min(c[k] for c in cx) for k in range(4))
                spa = max(max(c[k] for c in ca) - min(c[k] for c in ca) for k in range(4))
                d = max(abs(medx[k] - meda[k]) for k in range(4))
                if spx: own_var_x += 1
                if spa: own_var_a += 1
                if spx or spa: own_var_lines.add(li)
                worst_color = max(worst_color, d)
                if d > COLOR_TOL:
                    bad += 1; first = first or (li, ti, f'cor x64 med {medx} vs arm64 med {meda}')
            else:
                fx = [float(v) for v in vx]; fa = [float(v) for v in va]
                mx, ma = statistics.median(fx), statistics.median(fa)
                if max(fx) != min(fx): own_var_x += 1; own_var_lines.add(li)
                if max(fa) != min(fa): own_var_a += 1; own_var_lines.add(li)
                d = abs(mx - ma)
                if X[0][li][0] == 'info':
                    worst_color = max(worst_color, d * 255)
                    if d * 255 > COLOR_TOL + 1e-6:
                        bad += 1; first = first or (li, ti, f'info x64 med {mx} vs arm64 med {ma}')
                    continue
                rel = d / max(abs(mx), abs(ma)) if d > ABS else 0.0
                worst_rel = max(worst_rel, rel)
                if d > ABS and rel > REL:
                    bad += 1; first = first or (li, ti, f'num x64 med {mx} vs arm64 med {ma}')
    print(f'{len(X)} execucoes x64, {len(A)} ARM64, {len(X[0])} linhas')
    print(f'variacao propria: x64 {own_var_x} tokens, ARM64 {own_var_a} tokens, {len(own_var_lines)} linhas com variacao')
    print(f'pior divergencia (medianas): cor {worst_color:g}/255 (tol {COLOR_TOL}); numerico relativo {worst_rel*100:.3f}% (tol {REL*100:g}%)')
    if bad:
        li, ti, why = first
        print(f'FALHA: {bad} tokens fora da tolerancia; primeiro na linha {li+1} token {ti}: {why}')
        return 1
    print('OK: dentro da banda')
    return 0


sys.exit(main())
