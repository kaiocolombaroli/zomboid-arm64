# M2 pre-flight (Tarefa 1)

## Contagem por classe (168 importacoes unicas, `native/imports/classes.tsv`)

| Classe | Total |
|---|---|
| own | 103 |
| libcxx | 65 |

Por lib (importacoes de cada arquivo `native/imports/*.txt`):

| Lib | Importacoes | own | libcxx |
|---|---|---|---|
| lighting | 27 | 21 | 6 |
| popman | 121 | 69 | 52 |
| pathfind | 115 | 68 | 47 |
| bullet | 85 | 63 | 22 |
| clipper | 111 | 62 | 49 |

Os 65 `libcxx` existem todos como export de `libc++.dll` (checado por `pe_exports`; `llvm-nm -D` nao funciona na DLL PE).
A libc++ do llvm-mingw usa o mesmo namespace `__1` que a Apple; nao houve diferenca `__1` vs `__22`.

### Traducoes que mudam o nome (alvo != nome_apple)

- `__next_prime(unsigned long)` -> `...Ey`
- `basic_istream::read(char*, long)`, `basic_ostream::write`, `basic_streambuf::xsgetn/xsputn` -> `...x`
- `codecvt<char,char,__mbstate_t>::id` -> `_ZNSt3__17codecvtIcc9_MbstatetE2idE` (nome real: `_Mbstatet`)

### Pedidos `libcxx` que viraram `own` por falta de equivalente (13)

| Simbolo Apple | Motivo |
|---|---|
| `basic_ostream<char>::sentry` C1 e D1 (2) | inline na libc++ do llvm-mingw (diferenca de ABI inline); nao exportado |
| `_ZTI` de `basic_istream<char>`, `basic_ostream<char>`, `basic_streambuf<char>`, `ios_base::failure` (4) | typeinfo nao exportado por `libc++.dll` (so existe como simbolo `R` em `libc++.a`) |
| `_ZTI` de `exception`, `bad_alloc`, `bad_cast`, `runtime_error`, `length_error`, `out_of_range`, `bad_array_new_length` (7) | idem |

Os vtables (`_ZTV`) dessas classes de excecao estao exportados; so os typeinfos nao. Isso importa para a Tarefa 5: o `catch` por tipo precisa que o typeinfo proprio seja compativel (comparacao por nome) com o que a libc++.dll usa internamente.

### Notas de classificacao

- 10 simbolos `_ZGV*`/`_ZZ*` (statics locais e guards de `getIdentity`, `Instance`, `clipper`) sao definicoes fracas da propria dylib: classe `own`, `alvo` vazio; nao devem ser resolvidos pelo runtime.
- `__dynamic_cast` e `libcxx` (export presente). `_ZSt9terminatev` e `own` (runtime de excecoes da Tarefa 5). `_ZSt7nothrow` e `libcxx`.
- `alvo` fica vazio em toda linha `own`.

## unwind_stats (compact unwind, `__TEXT,__unwind_info`)

| Lib | frame | frameless | dwarf | other | with_lsda | dwarf+LSDA | pages_regular | pages_compressed |
|---|---|---|---|---|---|---|---|---|
| Lighting | 117 | 27 | 0 | 0 | 28 | 0 | 0 | 1 |
| PZPopMan | 313 | 83 | 0 | 0 | 133 | 0 | 0 | 1 |
| PZPathFind | 526 | 179 | 0 | 0 | 169 | 0 | 0 | 1 |
| PZBullet | 915 | 303 | 1 | 0 | 318 | 0 | 0 | 2 |
| PZClipper | 219 | 45 | 0 | 0 | 93 | 0 | 0 | 1 |

Criterio de parada do plano ("dwarf > 0 em funcao com LSDA nas 4 libs"): **nao disparou**. Nenhuma funcao tem modo DWARF e LSDA ao mesmo tempo, em nenhuma lib. Ha uma unica funcao em modo DWARF (Bullet), sem LSDA; o unwinder da Tarefa 5 encerra com registro se um unwind a atravessar.
Todas as paginas sao comprimidas (kind 3); nao ha paginas regulares.

## Fixture de excecoes (Passo 5)

- Fonte: `tests/fixtures/throw_probe.cpp` (sem headers Apple, `extern "C"` a mao).
- Compilar: `clang --target=arm64-apple-macos11 -nostdinc -fexceptions -O1 -ffixed-x18 -c tests/fixtures/throw_probe.cpp -o build/fixtures/throw_probe.o`
- Ligar: `ld.lld.exe -flavor darwin -arch arm64 -platform_version macos 11.0 11.0 -dylib -undefined dynamic_lookup -o build/fixtures/throw_probe.dylib build/fixtures/throw_probe.o`
- Resultado: **funciona**. `parse_macho` le `arm64_present=True`, `arm64e=False`; secoes incluem `__TEXT,__text`, `__TEXT,__gcc_except_tab`, `__TEXT,__unwind_info`; imports `__cxa_throw`, `__cxa_begin_catch`, `__gxx_personality_v0`, `_Unwind_Resume`; `unwind_stats`: frame 1, with_lsda 1, dwarf 0, 1 pagina comprimida. Modo alternativo nao foi necessario.

## Testes

`python -m unittest tests/tools/test_classify_imports.py -v` -> 11 testes, OK. `test_macho_inventory.py` + `test_gen_bridge.py` -> OK (sem regressao).
