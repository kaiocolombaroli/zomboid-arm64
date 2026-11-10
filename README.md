# Project Zomboid em ARM64 nativo (Windows on ARM)

[English](README.en.md)

Port experimental do Project Zomboid 42.21 (Build 42, modo solo) para rodar numa JVM ARM64 nativa no Windows on ARM, sem emulação x64 no processo do jogo.

**Projeto não oficial.** Não tem vínculo, apoio ou aprovação da The Indie Stone. Project Zomboid é propriedade dos seus donos. Este repositório **não contém o jogo nem nenhum arquivo dele**: cada pessoa precisa ter o jogo na própria conta Steam e baixar os insumos por conta própria (veja "Passo a passo"). Também não contém o FMOD.

## O que é

O Project Zomboid para Windows usa DLLs nativas x64 (física, iluminação, pathfinding, som etc.). Numa máquina ARM64 elas só rodam por emulação. Este projeto troca isso por:

- uma JVM 25 ARM64 (Microsoft Build of OpenJDK) e o LWJGL 3.4.1 ARM64;
- as bibliotecas nativas da versão **macOS** do jogo (o depot macOS tem código `arm64`), carregadas por um carregador Mach-O próprio, com uma camada que implementa o que elas esperam de libSystem e libc++;
- DLLs ponte ARM64 que expõem os métodos `Java_*` que o jogo espera;
- DLLs stub para rede, Steam, Bink e imgui, e as DLLs ARM64 oficiais do FMOD para o som;
- um agente Java (`pzarm-agent.jar`) que ajusta a renderização do jogo (contorno de um bug de textura do Mesa D3D12 e contorno parcial de um recolhimento de paredes).

Veja [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Ganho de desempenho

Medido num único aparelho (Surface Pro X, SQ1, 8 GB, Adreno 680), uma sessão curta de cada versão, com trajetos não idênticos: cerca de 3x de FPS no mundo (14,5 FPS na x64 emulada contra 43,3 FPS na ARM64; [reports/m4-resultado.md](reports/m4-resultado.md)). Carregar o mundo ficou cerca de 1,5x mais rápido. É uma medida pequena e não controlada; seu resultado pode ser diferente. O limite de 60 FPS do jogo pode ter limitado a ARM64. O modo FBO (padrão atual do launcher) não foi medido separadamente nessa comparação.

## Requisitos

- Windows 11 on ARM (ARM64), por exemplo Surface Pro X. Testado em 1 máquina.
- 8 GB de RAM ou mais (em 8 GB a memória livre fica perto do limite; feche outros programas).
- Project Zomboid **42.21** instalado pela Steam (versão Windows), na sua conta. Outras versões não foram testadas e o launcher recusa um `projectzomboid.jar` diferente do que gerou o `dist/` (código de saída 3).
- Acesso à sua conta Steam para baixar o depot macOS `108602` (o app 108600 tem um depot macOS).
- Conta gratuita no FMOD (fmod.com) para baixar o FMOD Studio API 2.03.09 para Windows. O FMOD **não** é redistribuído aqui.
- Microsoft Store: "OpenCL, OpenGL, and Vulkan Compatibility Pack" (dá OpenGL ao Windows on ARM via D3D12).
- PowerShell 5.1 ou 7 e Python 3 no PATH.
- Espaço em disco: cerca de 3 GB livres (estimativa) para JDK, toolchain e build.

## Passo a passo (do zero até abrir o jogo)

1. **Clone** este repositório e abra um PowerShell na pasta dele.
2. **Instale o jogo** pela Steam (Project Zomboid 42.21, Windows). Ele só é lido, nada é escrito na pasta do jogo. Se estiver em outro local, defina `$env:PZ_GAME_DIR` ou passe `-GameDir` aos scripts.
3. **Baixe o depot macOS 108602** com a sua conta Steam (as dylibs `.dylib` são a única parte de macOS usada). Duas opções:
   - Console da Steam (`steam://open/console`): `download_depot 108600 108602`. A Steam informa a pasta ao terminar (algo como `steamapps\content\app_108600\depot_108602`).
   - [DepotDownloader](https://github.com/SteamRE/DepotDownloader): `DepotDownloader -app 108600 -depot 108602 -os macos -username SEU_USUARIO -dir depot108602`.
   - Atenção: a versão do depot precisa corresponder ao jogo 42.21. Se o jogo for atualizado, o depot "atual" muda junto; use o `manifest` da época se precisar fixar (o `-manifest` do DepotDownloader). Esse download não foi verificado nesta documentação.
4. **Instale o FMOD Studio API 2.03.09** para Windows (fmod.com/download, requer login). Ele vem com as DLLs ARM64 em `api\core\lib\arm64\fmod.dll` e `api\studio\lib\arm64\fmodstudio.dll`.
5. **Instale o Compatibility Pack** pela Microsoft Store.
6. **Rode o setup**, apontando para o que você baixou:

   ```powershell
   .\setup.ps1 -MacDepotDir C:\caminho\do\depot108602 -FmodApiDir "C:\Program Files (x86)\FMOD SoundSystem\FMOD Studio API Windows"
   ```

   O script confere o ambiente, baixa o que é livre (JDK 25 ARM64, LWJGL 3.4.1 ARM64 do Maven Central, toolchain llvm-mingw/CMake/Ninja ARM64), copia as dylibs para `game-libs\macos-42.21` e as DLLs do FMOD para `third-party\fmod`, e lista o que faltar. `-CheckOnly` só verifica; `-NoDownload` não usa a rede.
7. **Compile**:

   ```powershell
   .\build.ps1
   ```

   Gera `dist\` (24 DLLs ARM64, `manifest.json` e `pzarm-agent.jar`). Com `-Test` roda também `ctest` e os testes Python.
8. **Abra o jogo**:

   ```powershell
   .\launcher\run-game.ps1
   ```

   `-DryRun` só valida e mostra a linha de comando. Os dados do jogo (saves, configurações, logs do próprio jogo) ficam em `cache\`, não em `%USERPROFILE%\Zomboid`; na primeira execução o launcher copia o seu `options.ini` e desliga `doContainerOutline` (o contorno de contêineres falha no OpenGL via D3D12). Logs em `logs\`.

## Opções do launcher

`.\launcher\run-game.ps1 [opções]`:

| Opção | Efeito |
|---|---|
| (padrão) | `fboRenderChunk=true`, `glsync=swapfence,uploadflush,otherflush` (fence atrasado de 1 quadro em vez de `glFinish`; contorno do hazard de sincronização CPU-GPU do Mesa GLon12) com `vbosync=sync`, sem `renderfix`, e `collapse=player` (a casa à frente não abre de perto) |
| `-GlSync <lista>` | contorno de sincronização CPU-GPU: padrão `swapfence,uploadflush,otherflush`; também `swapfinish` (o `glFinish` antigo, que travava a CPU; custo medido: FPS mediana 60 → 37 numa sessão), `swapflush`, `modelfinish`, `modelflush` etc. (lista separada por vírgula) ou `none` (desliga) |
| `-NoFboChunk` | volta ao caminho antigo: `fboRenderChunk=false`, sem renderfix e sem collapse. Na percepção parece mais fluido |
| `-NoCollapseFix` | desliga só o `collapse=player` (a casa volta a abrir de perto) |
| `-VboSync <modo>` | contorno para buffers mapeados sem sincronizar (`glMapBufferRange` UNSYNCHRONIZED): `sync` (padrão; remove o UNSYNCHRONIZED), `invalidate` (como `sync` e invalida o buffer inteiro) ou `none`. O padrão foi validado só numa sessão curta, numa máquina |
| `-RenderFix <modo>` | contorno de textura, desligado por padrão (`none`, `flushtex` volta o contorno antigo, `flush`, `rebind`, `uniforms`, `dirty`, `dirtyprog`, `flushchunk`, `flushtexchunk`); veja [docs/RESULTS.md](docs/RESULTS.md) |
| `-NoSound` | joga sem som |
| `-NoAgent` | sem o agente Java (nenhum ajuste de renderização) |
| `-UseFboChunk` | aceito sem efeito (o modo FBO já é o padrão) |
| `-GameDir`, `-DistDir`, `-CacheDir` | caminhos; `-Timeout <s>`, `-DryRun`, `-Env`, `-PzSet`, `-RenderDiag`, `-DebugGl` para diagnóstico |

A descrição completa está no cabeçalho de `launcher\run-game.ps1`.

## Atalho no Iniciar

`launcher\jogar.ps1` é o seu perfil de inicialização: um bloco de configuração no topo (resolução, limite de FPS e contornos de sincronização) e uma chamada ao `run-game.ps1`; argumentos extras são repassados (por exemplo `-DryRun`). Se o jogo terminar com erro, a janela espera Enter para você ler a mensagem. `launcher\jogar.cmd` faz o mesmo a partir do Explorer.

`launcher\criar-atalho.ps1` cria (ou recria) o atalho `Project Zomboid ARM64` no Menu Iniciar, apontando para o `jogar.ps1` deste repositório:

```powershell
.\launcher\criar-atalho.ps1
```

## Testes

```powershell
python -m unittest discover -s tests/tools -t .      # ~4 min: launcher, geradores, manifesto
ctest --test-dir build --output-on-failure           # nativos (precisa do build)
.\build.ps1 -Test                                    # build + ctest + unittest
```

Os testes em `tests/harness/` (Java e PowerShell, um `run-*.ps1` por biblioteca nativa e `run-render-test.ps1`, `run-agent-test.ps1` para o agente) rodam as bibliotecas e o agente contra o jogo; precisam de `dist\` e do jogo, e abrem janelas e contexto OpenGL. Alguns comparam a saída ARM64 com a x64. Alguns testes de `tests/tools` e do ctest dependem do jogo ou do FMOD e são pulados ou falham se esses insumos não estiverem presentes.

## Limites conhecidos

- Só solo. Sem multiplayer e sem Steam (o jogo roda com `-nosteam`; sem Workshop e conquistas).
- O `glsync` padrão (`swapfence,uploadflush,otherflush` + `vbosync=sync`) é um **contorno**, não uma correção do Mesa. A causa provável é um hazard de sincronização CPU-GPU no Mesa GLon12/D3D12 (o x64 emulado também sofre): sprites com a textura errada e o boneco do menu sumindo. O diagnóstico `uimodeldiag` mostrou que o jogo desenha todo quadro e que forçar a sincronização resolve. O padrão troca o `glFinish` (que drenava a GPU e travava a CPU) por um fence atrasado de 1 quadro, que mantém CPU e GPU em paralelo; o `vbosync=sync` e os flushes de upload evitam a corrupção de textura que o fence sozinho deixa voltar. Validado em jogo (imagem limpa, FPS "melhor") apenas numa sessão curta, numa máquina, sem comparação controlada. `-GlSync swapfinish` volta ao `glFinish` antigo (custo medido: FPS mediana de 60 para 37 numa sessão). Um reprodutor isolado em OpenGL puro não reproduziu o problema.
- No modo FBO (padrão) o jogo pode parecer menos fluido que com `-NoFboChunk`, apesar de mostrar a cena correta. A comparação de FPS entre os modos (mediana 49 contra 60) foi em sessões diferentes e não é conclusiva.
- A casa à frente do jogador ainda pode se recolher de perto em algumas situações; o `collapse=player` reduz isso, mas não foi validado em todos os casos.
- Memória: em 8 GB a RAM livre chegou perto de zero durante o carregamento (54 MB no mundo com som, 6 MB no mínimo da sessão).
- Testado em 1 máquina (Surface Pro X), numa sessão curta por configuração. Voz (VoIP) não testada; os vídeos Bink ficam desativados (DLL stub).
- O port não altera o jogo; usa a versão 42.21. Outras versões podem quebrar as DLLs ponte.

## Solução de problemas

- `setup.ps1` lista "FALTA ...": cada linha diz o que fazer. Rode de novo depois.
- `ERRO: projectzomboid.jar diverge do manifesto` (saída 3): o jogo foi atualizado depois do `build.ps1`. Rode `.\build.ps1` de novo (a versão do jogo precisa ser a 42.21 compatível com as dylibs).
- `DLL ausente em dist` (saída 4): rode `.\build.ps1`. `DLL nao e arm64`: não copie DLLs x64 para `dist\`.
- Janela preta ou fecha na hora: confira o Compatibility Pack e o log mais recente em `logs\game-*.log` (a saída padrão e de erro vão para o fim do arquivo). `-DebugGl` grava diagnóstico de FBO em `logs\fbo-debug.log`.
- Sprites com textura errada: confira se o `glsync` efetivo no cabeçalho do log é `swapfence,uploadflush,otherflush` (ou `swapfinish`, se você pediu); teste `-NoFboChunk`.
- Sem som: confira `third-party\fmod` (versão 2.03.09, ARM64) e rode `python -I tools\check_fmod.py`. `-NoSound` desliga.
- Pouca memória: feche navegador e outros programas; reduza a resolução.
- Falha de compilação: rode `.\build.ps1 -Clean` e envie a saída em uma issue.

## Licença e créditos

Código deste repositório sob a [licença MIT](LICENSE). Veja [THIRD_PARTY.md](THIRD_PARTY.md): o carregador Mach-O e a camada Darwin seguem o projeto zomdroid (MIT), e as DLLs/dylibs do jogo e do FMOD não fazem parte do repositório e têm licenças próprias.

Histórico de pesquisa e decisões: [reports/](reports/) (resultados por etapa) e [docs/design/](docs/design/) (especificações e planos), escritos durante o desenvolvimento.
