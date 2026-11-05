"""Compara dois arquivos do LightingHarness.
Mesmo numero de linhas; tokens inteiros identicos; floats (contem '.') com diferenca relativa <= 1e-4.
Uso: python compare.py a.txt b.txt
"""
import re
import sys

REL = 1e-4
INT = re.compile(r'^[+-]?\d+$')
FLT = re.compile(r'^[+-]?\d+\.\d+(e[+-]?\d+)?$', re.I)


def tok_equal(a, b):
    if a == b:
        return True
    if FLT.match(a) and FLT.match(b):
        x, y = float(a), float(b)
        d = abs(x - y)
        return d <= REL * max(abs(x), abs(y)) or d == 0.0
    return False


def line_diff(la, lb):
    ta, tb = la.split(), lb.split()
    if len(ta) != len(tb):
        return 'numero de tokens difere'
    for a, b in zip(ta, tb):
        # chave=valor
        ka, _, va = a.partition('=') if '=' in a else ('', '', a)
        kb, _, vb = b.partition('=') if '=' in b else ('', '', b)
        if ka != kb or not tok_equal(va, vb):
            return f'token {a!r} != {b!r}'
    return None


def main():
    a = open(sys.argv[1], encoding='utf-8').read().splitlines()
    b = open(sys.argv[2], encoding='utf-8').read().splitlines()
    if len(a) != len(b):
        print(f'FALHA: linhas {len(a)} vs {len(b)}')
        return 1
    bad = 0
    for i, (la, lb) in enumerate(zip(a, b), 1):
        why = line_diff(la, lb)
        if why:
            bad += 1
            if bad == 1:
                print(f'FALHA: primeira divergencia na linha {i} ({why})\n  a: {la}\n  b: {lb}')
    if bad:
        print(f'{bad} linhas divergentes de {len(a)}')
        return 1
    print(f'OK: {len(a)} linhas comparadas, 0 divergencias')
    return 0


sys.exit(main())
