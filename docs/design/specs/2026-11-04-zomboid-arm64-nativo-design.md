# Project Zomboid nativo em ARM64 no Surface Pro X — design

Data: 2026-11-04. Status: aguardando revisão do usuário.

## 1. Objetivo

Rodar o Project Zomboid 42.21 em modo solo numa JVM ARM64 pura no Surface Pro X (SQ1, Adreno 680, 8 GB), sem emulação x64 no processo do jogo, e descobrir se isso melhora o desempenho de forma real.

Esta spec cobre as etapas M0 a M4. O som (M5) terá uma spec própria, escrita só se o M4 aprovar a continuidade.

### Critérios de sucesso

- O jogo entra num mundo descartável pela JVM ARM64, sem nenhum binário x64 no processo.
- O personagem anda por 5 minutos, abre contêineres e salva; ao recarregar, personagem e itens estão intactos.
- O FPS médio é medido com o mesmo método, save e trajeto no x64 e no ARM64.
- **Decisão no M4:** seguir para o M5 só se o ARM64 tiver FPS médio de pelo menos 1,3x o do x64.

### Fora do escopo

- Multiplayer, Steam, Workshop e conquistas.
- Som, vídeos Bink e qualquer mudança visual.
- Build 41, Android e outros aparelhos.
- Alterar arquivos da pasta do jogo ou distribuir binários do jogo.

## 2. Fatos medidos

| Fato | Evidência |
|---|---|
| JVM ARM64 e LWJGL 3.4.1 ARM64 sobem; o jogo para em `PZBullet64.dll` | `spike-java-arm/reports/spike-2.md` |
| 0 de 13 DLLs do jogo carregam na JVM ARM64 | `reports/load-probe.txt` |
| Código Java roda cerca de 2x mais rápido em 3 de 5 cargas; hashmap sem ganho | `reports/spike-3.md` |
| O gargalo é a `MainThread`, com 96% das amostras em Java; a GPU fica em cerca de 26% | `reports/session-20261104-103708-resumo.txt`, `reports/pz-20261104-111344.jfr` |
| O contorno de contêineres gera `GL_FRAMEBUFFER_UNSUPPORTED`; com `doContainerOutline=false`, não houve esse erro | `%USERPROFILE%/Zomboid\console.txt` |
| O depot macOS `108602` tinha `arm64` em Lighting, PopMan, PathFind, Bullet, Clipper e RakNet na 42.20 | `zomdroid/tools/macos-native-libs-brief.md` |

## 3. Abordagem escolhida

Portar o carregador Mach-O do Zomdroid para Windows ARM64 e rodar nele as libs nativas do macOS. Alternativas descartadas: processo auxiliar x64 (custo de comunicação nas chamadas frequentes) e JVM ARM64EC (exigiria portar o JIT).

## 4. Arquitetura

```text
JVM ARM64
  └─ System.loadLibrary("PZBullet64")
       └─ DLL ponte ARM64 (exporta Java_*)
            ├─ camada de chamada → código arm64 da dylib
            └─ carregador Mach-O → camada Darwin → Windows
```

Estrutura do projeto, em `projeto-zomboid-arm/`:

| Pasta | Responsabilidade | Depende de |
|---|---|---|
| `native/loader/` | Mapear Mach-O, aplicar rebase e bind, rodar construtores, resolver exports | `darwin/` |
| `native/darwin/` | Implementar as importações de libSystem e libc++ com semântica Apple | API Win32 |
| `native/bridge/` | Gerar as DLLs ponte com exports `Java_*` e ajustes de chamada | `loader/` |
| `native/stubs/` | DLLs ARM64 vazias para rede, Steam, FMOD e Bink | Nada |
| `native/thirdparty/` | Recompilar imgui-java e jassimp para ARM64 | Fontes oficiais |
| `launcher/` | Abrir o jogo com JVM, caminhos e cache isolados | `dist/` |
| `tests/` | Harness Java e nativo por lib | `dist/` |
| `dist/` | Saída final do build, fora do git | Build |

### Toolchain

- llvm-mingw com host e alvo `aarch64-w64-mingw32`, CMake e Ninja, instalados em `tools/` sem alterar o PATH do sistema.
- Motivo: o C++ usa ABI Itanium, a mesma família das dylibs, o que evita traduzir nomes do ABI da Microsoft.
- Todo binário gerado é conferido com `pe_arch.py`, e deve ser `arm64`.

### Reaproveitamento do Zomdroid

Código copiado de `zomdroid/app/src/main/cpp/`, mantendo licença e créditos:

| Origem | Uso |
|---|---|
| `macho_loader.c` | Base do carregador; `mmap`/`mprotect` viram `VirtualAlloc`/`VirtualProtect`, `dlsym` vira tabela interna e `GetProcAddress` |
| `macho_bridge.c` | Regras de argumentos Apple para a camada de chamada |
| `macho_string.cpp` | Funções de `std::string` com layout Apple |
| `macho_unwind.c`, `macho_eh.c` | Leitura de compact unwind para o runtime de exceções |
| `macho_jnienv.c`, `macho_jni_table.h` | Wrapper de `JNIEnv`, se o M0 mostrar uso variádico |

## 5. Regras de compatibilidade Apple → Windows

Tudo aqui é obrigatório na camada Darwin ou na ponte.

| Tema | Regra |
|---|---|
| Argumentos Java além de 8 inteiros ou 8 float | Ponte reempacota a pilha no formato compacto da Apple |
| `boolean`, `byte`, `char`, `short` | Ponte estende para 32 bits antes da chamada |
| Funções variádicas (`printf`, `vsnprintf` e similares) | Shim próprio lê a pilha no formato Apple e nunca repassa `va_list` direto |
| `long` | 64 bits na dylib e 32 no Windows; a camada usa só tipos de tamanho fixo |
| Memória | Um alocador único, com cabeçalho sobre `HeapAlloc`, atende `malloc`, `free`, `aligned_alloc` e `malloc_size` |
| Pilha | `___chkstk_darwin` percorre as páginas de guarda antes de a função usar frames grandes |
| TLS (Clipper) | Bootstrap próprio de `__thread_vars`, com `TlsAlloc` |
| `std::string` | Funções importadas seguem o layout alternativo da Apple |
| Streams de C++ | Usam a instância interna da dylib; qualquer importação fora da lista do M0 interrompe o carregamento com erro |
| Registrador x18 | Reservado nas duas plataformas; o código não pode alterá-lo |
| Cache de instruções | `FlushInstructionCache` depois de mapear e antes de executar |

### Exceções de C++

O `__register_frame` do Android não se aplica ao SEH do Windows. A camada Darwin terá um runtime próprio com `__cxa_throw`, `__gxx_personality_v0` e `_Unwind_Resume`, que percorre apenas frames de dylib usando compact unwind.

Uma exceção que chegue ao limite da dylib para a JNI registra a lib, a função e a mensagem, depois encerra o processo. Ela não atravessa frames do Java.

## 6. Fluxo de carregamento

1. O launcher usa o JDK 25 ARM64, com o diretório do jogo como diretório atual e `-cachedir` isolado.
2. `java.library.path` aponta somente para `dist/`, sem `.`, para que uma DLL x64 nunca seja escolhida por engano.
3. `org.lwjgl.librarypath` aponta para as DLLs ARM64 oficiais da LWJGL.
4. `System.loadLibrary("Lighting64")` encontra a DLL ponte em `dist/`.
5. A ponte carrega `libLighting.dylib`, roda os construtores e resolve todos os `Java_*` antes de retornar.
6. Se faltar qualquer símbolo, `JNI_OnLoad` falha, e a mensagem informa a lib e o nome faltante.

As DLLs ponte são geradas de duas fontes: exports da dylib e assinaturas dos métodos `native` do `projectzomboid.jar`. Arquivo gerado não é editado à mão.

Se o Java selecionar nomes ou caminhos com base em `os.arch`, um agente Java no launcher corrige isso em memória, sem tocar no jar.

## 7. Tratamento de erros

- Falha de carregamento: mensagem com a lib e o símbolo, sem queda silenciosa para outro binário.
- Importação desconhecida: o carregador recusa a dylib antes dos construtores e lista todas as importações faltantes de uma vez.
- Falha dentro da dylib: o handler de exceções do processo registra o endereço relativo à dylib, para localizar a função.
- Stub de rede ou Steam chamado: retorna o valor neutro do tipo e registra a primeira ocorrência de cada método.
- Logs ficam em `logs/`, e os saves de teste, em `cache/`.

## 8. Testes

| Nível | O que prova |
|---|---|
| `pe_arch.py` em `dist/` | Nenhum binário x64 |
| Harness Java por lib | Carrega a lib e chama funções reais, incluindo as de muitos argumentos |
| Testes da camada Darwin | Strings por imagem de bytes conhecida, alocador, variádicos, TLS e exceção forçada |
| Comparação com o x64 | Mesmas entradas e saídas nas funções puras de Clipper e PathFind |
| Jogo | Critérios da seção 1, em mundo descartável |

O FPS é medido por um agente Java mínimo que conta `glfwSwapBuffers`, usado sem alteração no x64 e no ARM64. A comparação usa `doContainerOutline=false` nos dois lados.

## 9. Etapas

Cada etapa só começa quando a anterior cumprir seu critério. Os tempos são estimativas com incerteza alta.

| Etapa | Entrega | Critério de saída | Tempo |
|---|---|---|---|
| M0 | Dylibs 42.21 e inventário | `arm64` presente; exports iguais aos das DLLs x64; lista fechada de importações; formatos suportados ou custo reavaliado | 1 dia |
| M1 | Toolchain, carregador, Darwin mínima, ponte de `Lighting` | Harness chama as funções de `Lighting`, incluindo `squareSetLightTransmission` (20 argumentos) | 1 semana |
| M2 | PopMan, PathFind, Bullet, Clipper | Harness de cada lib passa, incluindo exceção forçada | 1 a 2 semanas |
| M3 | Stubs, imgui e jassimp; launcher completo | Jogo chega ao menu principal | 1 a 2 semanas |
| M4 | Mundo, save e FPS | Critérios da seção 1 e decisão de continuidade | Alguns dias |

**M0 depende do usuário:** baixar o depot `108602` da versão atual com a própria conta Steam. Credenciais nunca passam pelo agente.

**Pare e reavalie se:** o M0 achar `arm64` ausente em alguma das 5 libs principais; a lista de importações for pelo menos 3x maior que a do Zomdroid; ou o OpenGL ARM64 falhar de um jeito que os ajustes de opções do jogo não contornem.

## 10. Riscos

| Risco | Efeito | Mitigação |
|---|---|---|
| Dylibs da 42.21 mudaram de formato | Carregador precisa de novos tipos de fixup | Verificado no M0 |
| Unwind sem suporte real no Windows | Salvar pode encerrar o jogo | Testado com exceção forçada no M2 |
| Atualização do jogo muda as libs | Build quebra | `dist/` guarda hash e versão; o launcher recusa divergência |
| OpenGL ARM64 se comporta diferente do x64 | Erros gráficos novos no M3 ou M4 | Compatibility Pack já instalado; investigar só se ocorrer |
| Stub incompleto para FMOD | Java falha ao inicializar o som | Stub gerado a partir de todos os métodos `native` |
| RAM de 8 GB | Pausas ou encerramento | Limite de heap igual ao do jogo; registrar RAM livre no M4 |
| Ganho abaixo de 1,3x | Trabalho sem retorno | Decisão explícita no M4 |

## 11. Regras de execução

- Opus planeja e revisa; Sonnet implementa; Haiku valida. Cada rodada usa um agente novo com escopo curto.
- Nada é escrito na pasta do jogo.
- Dylibs, jogos, JDKs e `dist/` ficam fora do git.
- Mundos de teste usam `-cachedir` isolado; os saves do usuário não são usados pela JVM ARM64.
