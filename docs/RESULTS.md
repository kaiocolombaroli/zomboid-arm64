# Resumo dos resultados técnicos

Resumo curto das etapas. Os relatórios completos estão em `reports/` (um por etapa) e `reports/spike/`. Os logs brutos de execução não fazem parte do repositório; os números abaixo vêm deles. Tudo foi medido num único aparelho (Surface Pro X, SQ1, 8 GB, Adreno 680).

| Etapa | Resultado |
|---|---|
| Spikes | A JVM ARM64 e o LWJGL 3.4.1 ARM64 sobem, mas 13 de 13 DLLs x64 do jogo falham ao carregar na JVM ARM64. O gargalo do jogo é a thread principal em Java (cerca de 96% das amostras); código Java rodou cerca de 2x mais rápido em 3 de 5 cargas de teste. |
| M0 a M2 | Inventário das dylibs do depot macOS, carregador Mach-O e camada Darwin; as bibliotecas Lighting, PopMan, PathFind, Bullet, Clipper e jassimp são carregadas por esse caminho e exercitadas pelos harnesses de `tests/harness`, que comparam a saída com a da versão x64 (detalhes em `reports/m1-resultado.md` e `reports/m2-resultado.md`). |
| M3 | Stubs de rede, Steam, Bink e imgui; o jogo chega ao menu principal sem emulação x64 no processo (65 s do launch ao menu, todos os módulos do processo ARM64). |
| M4 | FPS médio no mundo de 14,5 (x64 emulada) para 43,3 (ARM64), cerca de 3x, em uma sessão curta de cada, trajetos não idênticos. Carregar o mundo ficou 1,5x mais rápido. Bug de contorno de contêineres contornado com `doContainerOutline=false`. |
| M5 | Som com as DLLs FMOD oficiais ARM64 via ponte. FPS com som não caiu mais de 15% numa sessão; RAM livre chegou a 54 MB no mundo. |
| M6 | Bug de textura do Mesa D3D12 (sprites com a textura errada) contornado com `glFlush` seletivo (`renderfix=flushtex`); causa raiz não isolada. |
| Renderização (detalhes) | O FBO dos chunks só funciona com `textureCompression` ligado, por causa de um limite de memória de GPU de cerca de 1,73 GB (limite reportado neste aparelho). O caminho de desenho dos chunks em FBO mostra sprites de página de textura errada em GLon12 mesmo com o estado do OpenGL conferido e correto; um `glFlush` antes dos desenhos em que a textura muda (`flushtex`) contorna. O recolhimento de paredes perto do jogador é só parcialmente contornado (`collapse=player`). Hipóteses descartadas: `gl_FragDepth` e anel de VBO; um reprodutor isolado em OpenGL puro também não reproduziu o problema. |
| M7 | Reprodutor isolado em OpenGL puro não reproduziu o bug de textura; as hipóteses de `gl_FragDepth` e de anel de VBO foram descartadas. |

Limites: uma máquina, sessões curtas, sem comparação controlada; veja a seção "Limites conhecidos" do README.

Hipótese `vbosync` (descartada como padrão; opção `-VboSync sync`): o `glMapBufferRange` com UNSYNCHRONIZED (acesso 0x26) de `GLVertexBufferObject` no Mesa D3D12 deixa a CPU reescrever vértices que a GPU ainda não consumiu (sprites com textura/UV errados e piscando, com e sem FBO). Com `-VboSync sync` o agente remove o UNSYNCHRONIZED; ele passou a ser o padrão (`sync`) junto com o `glsync=swapfence,uploadflush,otherflush` (ver abaixo).

## Sincronização CPU-GPU (`glsync`)

Causa provável dos sprites com textura errada e do boneco do menu que some: um hazard de sincronização CPU-GPU no Mesa GLon12/D3D12 (o x64 emulado também sofre, então não é do port ARM64). O diagnóstico `uimodeldiag` provou que o jogo desenha o boneco em todo quadro (`render` igual aos swaps) e que, ao forçar a sincronização com dois `glReadPixels` por quadro, o boneco parou de sumir.

Contorno anterior (agora `-GlSync swapfinish`): `glsync=swapfinish`, um `glFinish` imediatamente antes de cada `glfwSwapBuffers`. Em jogo, `-GlSync swapfinish -RenderFix none -VboSync none` eliminou o glitch com FPS mediana 37; a combinação anterior (`swapfinish` + `flushtex` + `vbosync`) deu 19. Custo medido do `glFinish` do `swapfinish`: de 60 para 37 FPS de mediana, numa sessão e num aparelho (sem comparação controlada). Com o `glFinish`, `flushtex` e `vbosync` eram redundantes (`flushtex` volta com `-RenderFix flushtex`; `-GlSync none` desliga o `glsync`).

Padrão atual: `glsync=swapfence,uploadflush,otherflush` + `vbosync=sync`. O `glFinish` do `swapfinish` travava a CPU, porque drenava a GPU a cada quadro. O `swapfence` (fence `glFenceSync`/`glClientWaitSync` atrasado de 1 quadro) restaura a sobreposição CPU/GPU: a espera do fence apareceu perto de zero no overlay da tela, numa sessão curta (observação visual, não registrada no `glsync.log`). Sozinho, ele reintroduz a corrupção de textura, porque o jogo reescreve buffers sem sincronizar; `vbosync=sync` + `uploadflush` + `otherflush` limpam a imagem. Essa configuração foi validada em jogo numa sessão curta, numa máquina, com imagem limpa e FPS percebido como melhor; como o `glsync.log` dessa sessão não foi coletado, não há mediana de FPS medida nem comparação controlada. O `swapfinish` continua selecionável com `-GlSync swapfinish`.

É um contorno, não uma correção do Mesa.

Pendências conhecidas do `glsync`:

- Os uploads feitos pelos wrappers `IGLBufferObject` sincronizam duas vezes: uma no wrapper e outra no `GL15` que ele chama. A configuração validada em jogo (`swapfinish,uploadflush`) foi assim; não alterar sem um novo teste em jogo.
- O `uimodeldiag` chama `glGetError` ao capturar o estado e, com isso, limpa erros GL pendentes do jogo. É só diagnóstico e não faz parte da configuração padrão.
