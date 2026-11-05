"""Compara duas saidas do BulletHarness (x64 x ARM64) com tolerancia ABSOLUTA em floats.
- Linhas "step=N ...": cada token chave=valor (valores separados por ',' contam como varios) e comparado; floats (com '.')
  por diferenca absoluta, inteiros (sem '.') exatamente iguais. Reporta a maior diferenca absoluta de cada passo.
- Criterio de aprovacao: no ULTIMO passo (120) todo float com diferenca absoluta <= LIMIT (1e-3) e todos os inteiros iguais.
- Passos intermediarios acima do limite nao reprovam sozinhos, mas sao marcados INVESTIGAR (diferenca cumulativa).
- Linhas "post=N" (fase posterior ao passo 120): mesma tolerancia, entram no maior-por-passo e nos totais.
- Demais linhas: tokens identicos (texto exato).
Uso: python compare_float.py a.txt b.txt [--limit 1e-3] [--final 120] [--table]
Saida 0 = PASS; 1 = FAIL; 2 = uso/formato.
"""
import re
import sys

FLT = re.compile(r'^[+-]?\d+\.\d+(e[+-]?\d+)?$', re.I)
INT = re.compile(r'^[+-]?\d+$')


def parse(path):
    steps, other = {}, []
    for ln, line in enumerate(open(path, encoding='utf-8').read().splitlines(), 1):
        if line.startswith('step=') or line.startswith('post='):
            k, _, v = line.split()[0].partition('=')
            n = int(v) if k == 'step' else 1000 + int(v)     # post=N vira chave 1000+N
            steps[n] = (ln, line)
        else:
            other.append((ln, line))
    return steps, other


def values(tok):
    k, eq, v = tok.partition('=')
    return k, (v.split(',') if eq else [])


def cmp_line(la, lb):
    """Devolve (maior diferenca de float, lista de problemas inteiros/estrutura)."""
    ta, tb = la.split(), lb.split()
    if len(ta) != len(tb):
        return 0.0, ['numero de tokens difere (%d x %d)' % (len(ta), len(tb))]
    worst, bad = 0.0, []
    for a, b in zip(ta, tb):
        ka, va = values(a)
        kb, vb = values(b)
        if ka != kb or len(va) != len(vb):
            bad.append('chave/estrutura %r x %r' % (a, b))
            continue
        for x, y in zip(va, vb):
            if FLT.match(x) and FLT.match(y):
                worst = max(worst, abs(float(x) - float(y)))
            elif x != y:
                bad.append('%s: %s != %s' % (ka, x, y))
    return worst, bad


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    limit, final, table = 1e-3, 120, '--table' in sys.argv
    for i, a in enumerate(sys.argv):
        if a == '--limit':
            limit = float(sys.argv[i + 1])
            args = [x for x in args if x != sys.argv[i + 1]]
        if a == '--final':
            final = int(sys.argv[i + 1])
            args = [x for x in args if x != sys.argv[i + 1]]
    if len(args) != 2:
        print(__doc__)
        return 2
    sa, oa = parse(args[0])
    sb, ob = parse(args[1])
    fail = False
    if sorted(sa) != sorted(sb):
        print('FALHA: passos diferentes (%d x %d)' % (len(sa), len(sb)))
        return 1
    if final not in sa:
        print('FALHA: passo final %d ausente' % final)
        return 1
    if len(oa) != len(ob):
        print('FALHA: linhas fora dos passos: %d x %d' % (len(oa), len(ob)))
        fail = True
    for (la, a), (lb, b) in zip(oa, ob):
        if a.split() != b.split():
            print('FALHA: linha %d/%d difere\n  a: %s\n  b: %s' % (la, lb, a, b))
            fail = True
    worst_all, worst_step, int_bad, over = 0.0, 0, 0, []
    for n in sorted(sa):
        d, bad = cmp_line(sa[n][1], sb[n][1])
        if table:
            print('%s %3d maior_dif=%.3e%s' % ('passo' if n < 1000 else 'post ', n if n < 1000 else n - 1000, d, '  INT/ESTRUTURA' if bad else ''))
        if bad:
            int_bad += 1
            print('FALHA passo %d: %s' % (n, '; '.join(bad[:3])))
        if d > worst_all:
            worst_all, worst_step = d, n
        if d > limit:
            over.append(n)
        if n == final:
            fd, fbad = d, bad
    print('maior diferenca absoluta no passo %d: %.3e (limite %.0e)' % (final, fd, limit))
    print('maior diferenca em qualquer passo: %.3e (passo %d)' % (worst_all, worst_step))
    if over:
        print('INVESTIGAR: %d passos acima do limite (primeiro %d, ultimo %d)' % (len(over), over[0], over[-1]))
    if int_bad:
        fail = True
    if fd > limit:
        fail = True
    if fail:
        print('FALHA: %d passos, passo final %s' % (len(sa), 'reprovado' if (fd > limit or fbad) else 'ok'))
        return 1
    print('OK: %d passos + %d linhas fixas comparados, passo %d dentro de %.0e' % (len(sa), len(oa), final, limit))
    return 0


sys.exit(main())
