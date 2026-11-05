# Project Zomboid ARM64: som (M5) — design

Data: 2026-11-05. Status: aguardando revisão do usuário.

Continua `2026-11-04-zomboid-arm64-nativo-design.md`, que define arquitetura, toolchain e regras gerais.

## 1. Objetivo

Ter o som do Project Zomboid 42.21 funcionando em modo solo na JVM ARM64, sem nenhum binário x64 no processo.

### Critérios de sucesso

- O jogo roda sem `-nosound`, e o log mostra `FMOD::Studio::System::Initialize()` sem erro.
- O usuário ouve música no menu, sons do mundo, passos, zumbis e combate, com pausa e retomada.
- Em 10 minutos de jogo não há travamento nem chiado contínuo.
- O FPS no mundo cai no máximo 15% em relação a `-nosound`, medido com o mesmo contador.
- Fechar o jogo termina o processo sem travar.

### Fora do escopo

- Chat de voz, captura de microfone, multiplayer, Steam e vídeos Bink.
- Distribuir DLLs do FMOD ou binários do jogo.

## 2. Fatos medidos

| Fato | Evidência |
|---|---|
| O jogo usa FMOD 2.3.9 | Versão de `fmod.dll` e `fmodstudio.dll` |
| A integração macOS tem 437 `Java_*`, iguais à DLL x64 | `reports/m0-inventario.md` |
| Ela importa 598 símbolos: 367 C do FMOD, 70 ligados a FMOD em C++, 101 já atendidos, 24 da própria dylib, 45 novos | Inventário de 2026-11-05 |
| Todos os 367 existem em `fmod.dll` ou `fmodstudio.dll` x64 | Mesmo inventário |
| A `libfmod.dylib` depende de CoreAudio, AVFoundation e Objective-C | `reports/m0-inventario.md` |

## 3. Arquitetura

```text
Java (fmod.javafmodJNI)
  └─ fmodintegration64.dll (ponte ARM64)
       ├─ carregador Mach-O → libfmodintegration.dylib
       ├─ camada Darwin → pthread, dispatch, stdio
       └─ pzfmodshim → fmod.dll e fmodstudio.dll ARM64 oficiais
```

| Peça | Responsabilidade |
|---|---|
| `native/darwin/dw_pthread.c` | `pthread_*`, `sched_*`, `dispatch_semaphore_*` com layout Apple sobre Win32 |
| `native/darwin/dw_table.c` e geradores | Novas importações: `sprintf`, `vfprintf`, `qsort`, `time`, `ctime_r`, `localtime_r` e demais |
| `native/fmodshim/` | Resolve `FMOD_*` por `GetProcAddress` e traduz C++ para C |
| `fmod.dll` e `fmodstudio.dll` | DLLs ARM64 oficiais, baixadas pelo usuário, em `dist/` e fora do git |
| `stub` de `fmodintegration64` | Sai do build padrão, e `fmod.dll`/`fmodstudio.dll` vazias são substituídas |

`System.loadLibrary("fmod")` e `"fmodstudio"` passam a carregar as DLLs oficiais. `pzdarwin.dll` não as importa em tempo de link: `fmodintegration64.dll` as resolve no `JNI_OnLoad`.

## 4. Regras de compatibilidade

| Tema | Regra |
|---|---|
| `pthread_mutex_t` | 64 bytes Apple; guarda ponteiro ou `SRWLOCK` e tipo; recursivo por atributo |
| `pthread_cond_t` | 48 bytes Apple, sobre `CONDITION_VARIABLE` |
| `pthread_once_t` | 16 bytes Apple, com inicialização única |
| `pthread_t` | Ponteiro opaco próprio |
| `dispatch_semaphore_t` | Objeto próprio sobre semáforo Win32 |
| Tempo | `pthread_cond_timedwait` usa `timespec` Apple em tempo absoluto |
| `__stderrp`, `__stdoutp` | Ponteiros para `FILE*` do ucrt |
| `vfprintf`, `sprintf` | Formato Apple, com `dw_fix_format` |
| Callbacks do FMOD | Mesma convenção AAPCS nos dois lados; chamadas da Windows para dylib passam por shim se tiverem argumentos de pilha |
| Thread nativa | `JavaVM` wrapper, com `AttachCurrentThread` |
| Estruturas FMOD | Sem `long` na API; conferir `FMOD_ADVANCEDSETTINGS`, `FMOD_CREATESOUNDEXINFO` e `FMOD_STUDIO_ADVANCEDSETTINGS` pelo campo `cbsize` |

## 5. Tratamento de erros

- DLL do FMOD ausente ou x64: o launcher encerra com código 4, citando o arquivo.
- Versão diferente: `FMOD_ERR_HEADER_MISMATCH` é registrado com as duas versões.
- Símbolo FMOD ausente: `JNI_OnLoad` falha e lista todos os nomes.
- `-nosound` continua disponível no launcher como `-NoSound`.

## 6. Testes

| Nível | O que prova |
|---|---|
| Testes de `dw_pthread` | Mutex, recursivo, cond com timeout, once, 8 threads, join |
| Cobertura de importações | 598 resolvidos ou com alvo em DLL |
| Harness `FmodHarness` | Inicializa o sistema, carrega um banco do jogo, toca evento e confere estado, sem o jogo |
| Comparação x64 | Mesmos resultados nas chamadas de estado |
| Jogo | Critérios da seção 1, com o usuário ouvindo |

## 7. Etapas

| Etapa | Entrega | Tempo |
|---|---|---|
| A | Usuário baixa FMOD; conferir ARM64 e exports | 1 dia |
| B | `dw_pthread` e importações | 2 dias |
| C | Ponte e shim; harness toca um banco | 2 a 4 dias |
| D | Jogo com som e medições | 1 a 2 dias |

**Pare e reporte se:** a distribuição do FMOD não tiver DLL ARM64; faltar um dos 367 símbolos; ou a versão disponível for incompatível com os bancos do jogo.

## 8. Riscos

| Risco | Efeito | Mitigação |
|---|---|---|
| Versão 2.03.09 indisponível | Bancos ou integração falham | Aceitar a 2.03.xx mais próxima e testar |
| RAM com 8 GB | Encerramento | Medir RAM livre mínima |
| Thread do mixer em núcleo lento | Chiado | Tamanho de buffer do FMOD, sem mudar critérios |
| Callback de thread nativa | Falha ao chamar Java | Coberto no harness |
| WebRTC na integração | Código sem teste | Voz fora do escopo |

## 9. Regras de execução

Iguais às do M0 a M4: Opus planeja, Sonnet implementa, Haiku valida, sem commit até o usuário pedir, e nada é escrito na pasta do jogo.
