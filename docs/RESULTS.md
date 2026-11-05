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
