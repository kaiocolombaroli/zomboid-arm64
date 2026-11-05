# Arquitetura

Visão curta de como o jogo roda numa JVM ARM64 sem DLLs x64. Detalhes e histórico em `reports/` e `docs/design/`.

```text
JVM 25 ARM64 (+ LWJGL 3.4.1 ARM64)
  |-- pzarm-agent.jar  (-javaagent: ajustes de renderização no bytecode do jogo)
  `-- System.loadLibrary("PZBullet64" ...)
        `-- DLL ponte ARM64 (exporta Java_*)             native/bridge, native/loader/macho_bridge.c
              |-- ajuste de ABI (JNI Apple -> AAPCS64)    thunks gerados por tools/gen_bridge.py
              `-- pzdarwin.dll
                    |-- carregador Mach-O                  native/loader
                    `-- camada Darwin (libSystem, libc++)  native/darwin
```

## Carregador Mach-O (`native/loader/`)
Mapeia a dylib `arm64` do depot macOS na memória do processo Windows, aplica rebase e bind, roda os inicializadores, resolve
exports e importações (uma tabela própria, mais um resolvedor externo para `FMOD_*`) e trata TLV (variáveis thread-local).
Qualquer importação sem implementação é recusada na carga. O registrador `x18` é reservado (`-ffixed-x18`), porque o Windows o usa
para a TEB.

## Camada Darwin (`native/darwin/`)
Implementa, com a semântica da Apple, o que as dylibs importam: alocação, `stdio`, arquivos, `pthread`, matemática, formatação
(`printf` variádico), runtime de exceções C++ (desenrolador de compact unwind) e a ABI libc++ (`std::string`, `iostream` etc., com
`libc++`/`libc++abi`/`libunwind` ligadas estaticamente: nenhuma `.dll` irmã). Tudo vai para `pzdarwin.dll`. As listas de símbolos em
`native/imports/` dizem o que cada dylib precisa e os testes conferem a cobertura.

## Pontes (`native/bridge/`, `tools/gen_bridge.py`)
Uma DLL por dylib (Lighting64, PZPopMan64, PZPathFind64, PZBullet64, PZClipper64, jassimp64, fmodintegration64). Os nomes `Java_*` e
os descritores são lidos do `projectzomboid.jar` e das dylibs do próprio usuário (só leitura, na máquina dele); nenhum binário do jogo é copiado para o repositório nem para `dist`. Cada método vira um thunk que ajusta a ABI (a convenção da
Apple e a AAPCS64 divergem em argumentos de pilha e `bool`). A ponte FMOD (`native/fmodshim/`) liga a `libfmodintegration.dylib` às
DLLs oficiais ARM64 do FMOD: funções C diretas e shims para os 61 métodos C++ de `FMOD::Studio`.

## Stubs (`native/stubs/`)
DLLs ARM64 sem funcionalidade para rede (ZNetNoSteam64, ZNetJNI64), Bink, imgui-java64, Steam e RakNet; os nomes `Java_*` exportados são lidos
das DLLs x64 do jogo instalado pelo próprio usuário (só leitura; nenhum binário é copiado) e recriados como funções vazias, para o jogo carregar sem erro. O jogo roda com `-nosteam`.

## Agente Java (`launcher/agent/`)
`pzarm-agent.jar` é carregado com `-javaagent` e reescreve, na carga, classes do jogo com `java.lang.classfile` (JDK 25): o estado de
`PerformanceSettings` (`pz=`), o contorno de textura do Mesa D3D12 (`renderfix=`), o `collapse=player` e os contadores de FPS.
Nada é gravado no jar nem na pasta do jogo.

## Launcher e dist (`launcher/run-game.ps1`, `tools/make_manifest.py`)
`build.ps1` gera `dist/` (DLLs ARM64 e `manifest.json` com tamanho e SHA-256 de cada DLL e do `projectzomboid.jar`). O launcher
confere arquitetura e hash antes de abrir, nunca aponta `java.library.path` para a pasta do jogo e isola os dados em `cache/`.

## Testes
`tests/darwin`, `tests/loader`, `tests/fixtures` (Mach-O sintéticos), `tests/tools` (Python), `tests/harness` (Java: roda cada
biblioteca e o agente).
