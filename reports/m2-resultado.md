# M2 - Resultado (Tarefa 7: Clipper, PathFind e PopMan)

PopMan (Parte B) e Bullet (Parte C): secoes finais deste arquivo. Com Bullet, as 4 libs da Tarefa 7 estao comparadas.

## Resultado
| Lib | Comparacao | Resultado |
|---|---|---|
| Clipper (`PZClipper64` x `libPZClipper.dylib`) | `python tests/harness/compare.py reports/native-clipper-x64.txt reports/native-clipper-arm64.txt` | `OK: 137 linhas comparadas, 0 divergencias` |
| PathFind (`PZPathFind64` x `libPZPathFind.dylib`) | `python tests/harness/compare.py reports/native-pathfind-x64.txt reports/native-pathfind-arm64.txt` | `OK: 99 linhas comparadas, 0 divergencias` |

Criterio do plano: `compare.py` com tolerancia relativa 1e-4 para floats (tokens inteiros identicos, mesmo numero de linhas). Nao foi alterado.
O ARM64 foi executado varias vezes (inclusive depois da correcao de `dw_ti.cpp` e do rebuild de `pzdarwin.dll`): sempre 0 divergencias contra a mesma saida x64.
Execucao: `tests\harness\run-native.ps1 -Lib clipper|pathfind -Arch x64|arm64` (saida 0 nas duas arquiteturas; sem hs_err em `logs\`).
Metadados em `reports/native-<lib>-<arch>.meta`: x64 = `os.arch = amd64` (jre64 do jogo, java 25.0.1); ARM64 = `os.arch = aarch64` (JDK ARM64, java 25.0.5.0.2).
Nenhum crash e nenhum bug de `native/` apareceu em Clipper/PathFind; so houve correcoes do harness (abaixo).

## Cenarios
### Clipper (19 exports: Clipper 13 + ClipperOffset 6) - `tests/harness/ClipperHarness.java`
Ordem tirada do bytecode (`Zone`, `VisibilityGraph`, `CollideWithObstaclesPoly`, `WorldMapGeometry`, `HLChunkRegion`, `FBORenderOcclusion`, `Zone.checkPolylineOutline`).
Buffers `allocateDirect` na ordem padrao (big-endian), como no jogo; o nativo le/escreve em big-endian (testado: little-endian da 0 poligonos no x64 tambem).
1. `Clipper.init()`, `new Clipper` (n_init, newInstance).
2. `Zone.difference`: clear, addPath(n,buf,false), clipAABB, generatePolygons(), getPolygon (L e estrela); `addPath` de 4 argumentos nas 4 combinacoes de booleanos.
3. `VisibilityGraph`: anel de 16 AABB (gera buraco) + bloco separado, getPolygon com anel externo + buracos; `addAABBBevel`.
4. `CollideWithObstaclesPoly`: 3 `addPolygon`, uniao e leitura.
5. `Zone.getPolygonTriangles`: addPath, generatePolygons, `triangulate` (L e estrela).
6. `WorldMapGeometry`: poligono com buraco, clipAABB, `generatePolygons(D)` com D=0,0.5,2.0, `generatePolygons(D,I)`, `triangulate2` (vertices + indices).
7. `HLChunkRegion`/`FBORenderOcclusion`: `generatePolygons(D,I)` e `generatePolygons(I,D,I)`.
8. Fora do bytecode do jogo (cobertura dos exports): `addLine`, `generateTriangulatePolygons`.
9. `ClipperOffset`: sequencia de `Zone.checkPolylineOutline` (largura 3 e 4, Miter+Butt, `flip` antes de `addPath`), 4 JoinType x 5 EndType, delta negativo; `clear/addPath/execute/getPolygonCount/getPolygon`.
10. Entradas degeneradas: caminho de 2 pontos e `generatePolygons` com Clipper vazio (retornam 0 sem excecao).
Total de poligonos gerados nos cenarios: 45 (o harness falha se < 20). 137 linhas comparadas.

### PathFind (11 exports de `PathfindNative`) - `tests/harness/PathFindHarness.java`
Ordem de `PathfindNative.init(IsoMetaGrid)` e `PathfindNativeThread.updateThread` (chunk, quadrado, veiculo, `update()`, pedidos), na thread "PathfindNativeThread".
Mapa pequeno e fixo inventado: 1 cell, chunks 0..7 x 0..7 (64x64 quadrados, nivel 0 = 32), parede vertical (x=30, y<40) com abertura, cerca com trechos quebravel/pulavel/alta/janela,
parede de borda oeste com trecho quebravel, planta de plantacao, faixa de custo, uma rampa.
1. `init` (loadLibrary), `initWorld`, 64x `updateChunk` (layout de `ChunkUpdateTask`), `updateSquare` (abre um quadrado da parede), `update`.
2. 86 pedidos `findPath` (layout de 47 bytes de `PathfindNative.findPath`): jogador/NPC/outro/zumbi, desvio de parede, faixa de custo, mesma casa, fora do mundo, chunk nao carregado,
   matriz de 32+32 combinacoes de flags (thump, climb fence, climb tall, hasTarget, canBend, avoidFarming) sobre a cerca, rastejar com e sem `ignoreCrawlCost`, `doNotRelease`, faixa de niveis.
3. Veiculo: `addVehicle`, pedidos, `teleportVehicle`, pedidos, `removeVehicle`, segundo `addVehicle`, `removeVehicle`.
4. `removeChunk` + pedido, recarga do chunk + pedido, `destroyWorld`, `freeMemoryAtExit`.
Resultado: 86/86 pedidos com `ret=1` (incluindo alvo fora do mundo, o nativo devolve rota direta). 99 linhas comparadas.
Sensibilidade: o veiculo so bloqueia com `VehiclePoly.z = 32` (com 0.0 o caminho ficava reto); isso foi descoberto no x64 e corrigido no harness.

## Correcoes de harness (x64 e a referencia)
- `PathFindHarness`: z do poligono do veiculo = 32 (acima). Nada mais precisou mudar.
- Forma de comparacao nao tolera `ret` diferente: `ret=` e inteiro e entra na comparacao.

## Correcoes iniciais pendentes (feitas)
- `native/darwin/dw_ti.cpp` linhas 30, 82, 87: `long` -> `int64_t` (layout Apple: `__base_class_type_info::__offset_flags` de 64 bits, tambem no x64/LLP64 onde `long` tem 32 bits).
- `tests/darwin/test_eh.c`: o clone usa agora ponteiro de nome diferente (`strdup` do nome, com o bit 63 mascarado antes de ler) e ha um segundo clone com o bit 63 ligado.
  Achado: o ponteiro de nome do typeinfo real ja traz o bit 63; um `strdup` sem mascarar dava SEGFAULT (erro do teste novo, corrigido). `ctest`: 55/55 passam.

## Tempos (uma rotina pesada por lib; `reports/native-<lib>-time-<arch>.txt`)
| Lib | Rotina | x64 (media / mediana) | ARM64 (media / mediana) |
|---|---|---|---|
| Clipper | 300 iteracoes (50 de aquecimento) de 400 AABB pseudo-aleatorias + uniao + leitura de todos os poligonos | 1535.3 us / 1484.5 us | 744.2 us / 725.4 us |
| PathFind | 1000 `findPath` (100 de aquecimento) em rotas aleatorias fixas no mapa 64x64 | 8509.9 us / 7470.6 us | 3255.0 us / 2871.9 us |
Checksums iguais nas duas arquiteturas (`chk=444061` Clipper; `found=1073 chk=4377` PathFind). Variacao entre execucoes do mesmo lado: ate ~5% (x64 Clipper mediu 1608 us noutra rodada).
Ressalva: nesta maquina (Windows ARM64) o JRE x64 do jogo roda sob emulacao, entao "ARM64 mais rapido" compara nativo com emulado e nao diz nada sobre o jogo em x64 real.
Tambem: o ARM64 usa JDK 25.0.5 e o x64 25.0.1 (JIT diferente).

## O que a validacao nao cobre
- Excecao de C++ real: nenhuma entrada Java alcanca um `throw` nestas duas libs (so entradas validas e degeneradas foram testadas, sem excecao). O criterio de excecao fica com o fixture da Tarefa 5.
- Nao rodam: `PathfindNativeRenderer.renderNative/setDebugOption` (precisam de GL), debug draw.
- Nao e o jogo: nenhum `IsoChunk/IsoGridSquare/BaseVehicle` real; os campos de bits/custo, o layout de buffer e `loadId` seguem o bytecode, mas os valores sao inventados. Um mapa real, multiplos niveis, escadas e muitos veiculos nao foram exercitados.
- Os argumentos inteiros de `generatePolygons(I,D,I)`/`(D,I)` e a semantica dos booleanos de `addPath` foram escolhidos por varredura, nao pelo conhecimento do significado.
- `addLine`, `generateTriangulatePolygons`, `addAABBBevel` nao sao chamadas pelo jogo; so cobrem o export.
- Threading: tudo numa thread so (a "PathfindNativeThread" do jogo). Concorrencia (Clipper usado por varias threads do jogo; TLV) nao foi exercitada.
- Saida e uma amostra: o ARM64 foi comparado com 1 execucao x64 (a saida e deterministica; execucoes repetidas dos dois lados deram o mesmo texto).
- Precisao: tolerancia 1e-4 relativa, como no plano; as saidas de Clipper/PathFind foram identicas ao texto (6 casas) nas duas arquiteturas.


---

# Tarefa 7, Parte B - PopMan (`PZPopMan64` x `libPZPopMan.dylib`)

## Resultado
| Verificacao | Resultado |
|---|---|
| Cenario deterministico: `compare.py` (1e-4) sobre `reports/native-popman-<arch>.txt` sem as linhas `#rand` | `OK: 161 linhas comparadas, 0 divergencias` |
| Bytes do save (6 arquivos em `build/harness/popman-<arch>/snapshot/`), comparacao byte a byte | todos iguais: `zpop_0_0` 14049, `zpop_0_1` 13785, `zpop_1_0` 14071, `zpop_1_1` 13807, `zpop_ids` 6, `zpop_virtual` 841 (total 56559 bytes) |
| Excecao real de C++ (save corrompido, `POPMAN_MODE=corrupt`) | `OK: 111 linhas comparadas, 0 divergencias`; stderr identico nas duas arquiteturas |
| Repovoamento aleatorio (`POPMAN_MODE=rand`), 4 execucoes de cada lado | mesma faixa (abaixo), sem criterio formal |

Execucao: `tests\harness\run-native.ps1 -Lib popman -Arch x64|arm64` (saida 0; sem `hs_err` em `logs\`). Nenhuma falha nativa em ARM64; nenhum bug de `native/` apareceu; nenhum arquivo de `native/` foi alterado nesta parte.
O `compare.py` roda sobre uma copia sem as linhas `#rand` (fase aleatoria): `grep -v '^#rand' reports/native-popman-<arch>.txt > x.txt`. `ctest`: 55/55.

## Como o PopMan e usado (bytecode)
`PZPopMan64` exporta 59 `Java_*`: `ZombiePopulationManager` 28, `MapCollisionData` 17, `MPDebugInfo` 8, `ZombiePopulationRenderer` 5 (GL, fora), `DebugCommands` 1.
O estado do jogo (pasta do save, modo, opcoes) chega por `MapCollisionData.n_setGameState` (`Core.GameSaveWorld`, `GameWindow.SaveDir`, `SavefileNaming.SUBDIR_ZPOP`, ...): o harness chama tambem natives de `MapCollisionData`.
Todos os natives sao `private static`, chamados por reflexao; nenhuma classe do jogo (`IsoChunk`, `IsoZombie`, `IsoMetaGrid`) foi criada.
Ordem: `n_setGameState` x14 -> `n_initMetaGrid/Cell/Chunk` (2x2 celulas, 32x32 chunks) -> `MCD.n_init` -> `ZPM.n_init` -> 12x `n_config*` (`onConfigReloaded`) -> `n_setOutfitNames`, `n_setSpawnOrigins` -> `n_loadedAreas`, `n_loadChunk` -> por quadro: `n_updateMain`, `n_getAddZombieCount/Data` (principal), `MCD.n_update` + `n_updateThread` (thread MCD, repetidas enquanto houver trabalho) -> `n_save`, `n_saveCell`, `n_stop`.
O nativo grava em `<SaveDir>/Sandbox/harness/zpop/` (`zpop_<x>_<y>.bin`, `zpop_virtual.bin`, `zpop_ids.bin`); a pasta e criada pelo harness, como o jogo faz.

## Cenarios (`tests/harness/PopManHarness.java`)
`random_device` (x64: DLL do jogo; ARM64: `arc4random` do shim) alimenta o repovoamento, `n_spawnHorde` e `n_worldSound`. Nao ha semente a fixar no x64 (DLL do jogo). Medido: duas execucoes x64 seguidas ja divergem (2437 x 2448 zumbis no primeiro quadro). O harness separa:
1. **Parte deterministica (comparada exatamente; 3 execucoes x64 deram o mesmo texto e os mesmos hashes)**: intensidade 0 em todos os chunks (nao repovoa); 400 zumbis explicitos por `n_addZombie` (LCG fixo, 4 celulas); chunks carregados (celula 0 e celula 1,1); 19 quadros de `updateMain` + thread; `n_registerZombie` x2, `n_updateRealZombies`, `n_aggroTarget`, `n_releaseZombie`, mais um `n_addZombie`; radar; `n_readyToPause/hasDataForThread/shouldWait`; `n_save`, `n_saveCell` x4, segundo `n_save` (bytes iguais ao primeiro nas duas arquiteturas); `n_stop`; recarga (novo `n_init` sobre os mesmos arquivos, 4 quadros, registros devolvidos); `n_save` apos a recarga; ida e volta (os devolvidos voltam por `n_addZombie`) e novo save.
2. **Fase aleatoria (so contagem, linhas `#rand`)**: `n_worldSound` + `n_spawnHorde`(20) depois do save comparado; os bytes do `save2` sao regravados em disco, entao a recarga vem exatamente deles.
3. **Modo `rand`**: intensidade variavel por chunk, repovoamento inicial de ~2700 zumbis, so informativo.
4. **Modo `corrupt`**: apos salvar, trunca as 4 celulas (1/3) e `zpop_virtual.bin` (1/2) e escreve `7fffffff` no inicio de `zpop_ids.bin`, recarrega, roda quadros e salva.

## Bytes do save e estado recarregado
- Os 6 arquivos sao identicos x64 x ARM64 (`cmp` e hashes `sha` nas linhas `file ...` do `.txt`, que o `compare.py` compara).
- Nenhum campo de tempo ou semente nos bytes: cabecalho `versao=7`, `4` nomes de outfit, registros; 3 execucoes x64 e as ARM64 deram os mesmos 6 hashes. `zpop_ids.bin` (6 bytes) guarda o contador de ids.
- Recarga: os 41 zumbis devolvidos (`x y z dir outfit flags px py pid`) sao identicos nas duas arquiteturas (linhas `add` do `.txt`).
- `reload_same_as_saved=false` e `roundtrip_same_as_saved=false` nas DUAS arquiteturas, iguais entre si: depois da recarga os zumbis dos chunks carregados viram "reais" (saem do arquivo da celula); ao devolve-los a ordem dos registros muda (mesmos tamanhos; so `zpop_virtual` fica identico). Nao e divergencia x64 x ARM64 e nao foi investigado mais.

## Excecao real de C++
Strings da dylib: `NewJavaException`, `ThrownJavaException`, `EOFException`, `IOException`, `FileNotFoundException`, `unknown file version "`, `ERROR loading `. A leitura de `zpop_*.bin` usa `ifstream`; o nativo faz `throw` e o `catch` da propria dylib imprime pelo `writeToStdErr` de Java e segue.
Provocado em `corrupt`: `zpop_ids.bin` com `7fff...` -> `IOException : unknown file version "32767"`; `zpop_virtual.bin` e as 4 celulas truncadas -> `EOFException : <caminho>` e `ERROR loading <caminho>`.
Resposta identica no x64 e no ARM64 (`reports/native-popman-corrupt-<arch>.stderr.txt`; so muda o separador de caminho, o ARM64 mostra `harness/zpop` porque a dylib usa `/`). Nenhum dos dois encerra o processo (a excecao nao chega ao limite da JNI); depois, o harness gera zumbis (11 no quadro 151) e salva (`corrupt_after`: hashes e tamanhos iguais, 111 linhas sem divergencia). Sem falha silenciosa em ARM64.
E o primeiro `throw` C++ real pego dentro de uma dylib rodando em ARM64 (a correcao `cpp_exception_jni_boundary` do Zomdroid nao foi necessaria nesta rota). A rota em que a excecao chega ao limite da JNI e encerra o processo continua coberta so pelo fixture da Tarefa 5.

## Repovoamento aleatorio (modo `rand`, informativo)
Primeiro quadro (`hours=100.250`): x64 2696, 2697, 2728, 2727; ARM64 2731, 2710, 2717, 2707 (rodadas iniciais com outro mapa, x64: 2437, 2442, 2448). `zpop_0_0.bin`: x64 83789..84339 bytes, ARM64 83701..84075. Mesma faixa (~1%).
Fase `#rand` (horda de 20 + som): x64 30 e 30; ARM64 21 e 30 (sempre >= 20).

## Tempos (`reports/native-popman-time-<arch>.txt`, ultima execucao)
| Rotina | x64 (media / mediana) | ARM64 (media / mediana) |
|---|---|---|
| `saveAll45`: `n_save` + 4 `n_saveCell`, 400 zumbis virtuais, 40 medidas (5 de aquecimento) | 1733.2 us / 1584.7 us | 706.9 us / 579.8 us |
| `frame200`: `n_updateMain` + contagem + `MCD.n_update` + `n_updateThread`, 200 quadros (20 de aquecimento) | 66.0 us / 31.4 us | 13.5 us / 7.6 us |
Mesma ressalva da Parte A: nesta maquina o JRE x64 roda emulado (ARM64 nativo x x64 emulado), JDK 25.0.5 x 25.0.1. Variacao entre execucoes do mesmo lado: ate ~15% (x64 `saveAll45` mediu 1680 e 1585 us). A rotina de salvar e dominada por E/S de arquivo; os maximos sao picos isolados.

## O que a validacao nao cobre (PopMan)
- Nenhuma classe real do jogo (`IsoZombie`, `IsoChunk`, `IsoMetaGrid`): mapa, intensidade, outfits e posicoes sao inventados; a ordem das chamadas segue o bytecode.
- Parte aleatoria (`n_spawnHorde`, `n_worldSound`, repovoamento): so contagem/faixa. `random_device` nao foi fixado (impossivel no x64), entao um defeito que so mude a distribuicao nao seria detectado.
- Nao rodam: `ZombiePopulationRenderer` (5, GL), `MPDebugInfo` (8), `DebugCommands.n_debugCommand`, `n_loadedAreas` com `server=true`, `n_config` (9 argumentos; o jogo atual usa `n_configFloat/Int`).
- `MapCollisionData`: so init/estado/update/save/stop; `n_pathTask`, `n_chunkUpdateTask`, `n_squareUpdateTask` nao foram chamados, entao a rota dos zumbis por colisao nao foi exercitada.
- Threading: tudo numa thread; a `MCDThread` real concorrente com a thread principal e o `saveLock` nao foram simulados.
- Excecao: so a rota em que a dylib pega a propria excecao; nenhuma entrada via Java a fez escapar ate a JNI.


---

# Tarefa 7, Parte C - Bullet (`PZBullet64` x `libPZBullet.dylib`)

## Resultado
| Verificacao | Resultado |
|---|---|
| `python tests/harness/compare_float.py reports/native-bullet-x64.txt reports/native-bullet-arm64.txt --table` | `OK: 131 passos + 21 linhas fixas comparados, passo 120 dentro de 1e-03` |
| Maior diferenca absoluta no passo 120 e em qualquer passo (131 linhas `step=`/`post=`) | **0** (saida com 9 casas decimais: floats identicos). Unica diferenca textual entre os arquivos: `-0.000000000` (x64) x `0.000000000` (ARM64) em `post=2 f8` (zero com sinal; diferenca numerica 0) |
| Inteiros (contagem de veiculos, retornos, id, contador `a`) | todos iguais nos 120 passos |
| Excecao Java lancada pelo nativo (`getOwnVehiclePhysics(9999,..)`) | `java.lang.RuntimeException: Vehicle not found` nas duas |
| Chamadas com id inexistente (`setVehicleParams`, `setVehicleMass`, `controlVehicle`, `setOwnVehiclePhysics`, `removeVehicle`) | iguais (`-1`, `-1`, sem excecao, `-4`, sem excecao) |

Execucao: `tests\harness\run-native.ps1 -Lib bullet -Arch x64|arm64` (saida 0 nas duas; sem `hs_err` em `logs\`). Nenhuma falha em ARM64. **Nenhum bug de `native/` encontrado; nenhum arquivo de `native/` foi alterado.** Criterio do plano mantido: 1e-3 absoluto no passo 120. `ctest`: 55/55; `unittest tests/tools`: 33 OK.
Metadados: x64 = `os.arch = amd64` (jre64 do jogo, 25.0.1); ARM64 = `aarch64` (JDK ARM64 25.0.5). O ARM64 carrega a dylib nativa (`arm64 slice, 1593 exports`, 98 entradas JNI, JNIEnv embrulhado por causa da JNI variadica).
Sensibilidade do comparador: uma copia adulterada (um inteiro `a=` e um `x` com +1e-3) reprova com saida 1.

## Cenario (`tests/harness/BulletHarness.java`, 98 exports `Java_zombie_core_physics_Bullet_*`)
Sem classes do jogo, exceto `Bullet` e um stub (abaixo). Ordem tirada do bytecode (`Bullet.init`, `WorldSimulation.create/update`, `VehicleScript.toBullet`, `CarController`, `IsoChunk.updatePhysicsForLevel/update`, `Bullet.updatePlayerList`):
1. `Bullet.init()` (carrega `PZBullet64`, `cmdBuf` direto little-endian de 4096, `initPZBullet`); `getPZBulletVersion` = `1.0.0.28`; `isWorldInit` false.
2. `defineVehicleScript("Base.HarnessCar", float[200])`: 4 rodas, caixa de chassi 1.0x0.6x2.2, massa 800 (layout copiado de `VehicleScript.toBullet`).
3. `initWorld(0,0,1,1,0,0,0,0,13)` (= `initWorld` de 7 + `activateChunkMap`), `clearPhysicsMeshes`, `definePhysicsMesh` e `defineVehiclePhysicsMesh` com um cubo (envoltoria convexa de faces coplanares).
4. Mundo minimo inventado: 7x7 chunks (56x56 quadrados), `Floor` em todos, um bloco `Solid` (x 36..37, y 20..36) no caminho; por chunk `setChunkMinMaxLevel` + comandos `cmdBuf` (8 = chunk, `updateChunk` x64, -1 -1) via `CatchToBullet`.
5. Personagem: jogador na lista (comando 12 via `CatchToBullet`, posicao muda a cada passo) e um `addPhysicsObject`. **Ragdoll nao foi exercitado** (ver lacunas).
6. Um veiculo (`addVehicle`, nariz para +x), `setTireInflation`, `setTireRemoved`, `setVehicleVelocityMultiplier`.
7. 120 passos de 50 ms (5 x `stepSimulation(0.01f,0,0f)`): `controlVehicle` (motor 3000 ate o passo 90, depois freio 400, direcao 0.08 entre 5 e 15), forca central no passo 50, torque no 60, lista de jogadores, `getVehicleCount`, `getVehiclePhysics`, `getObjectPhysics`. O veiculo acelera, encosta no bloco solido (~x=34.8, velocidade vai a 0) e fica parado.
8. Fase `post=` (extra): `teleportVehicle`, `setVehicleActive`, `setVehicleStatic`, `setVehicleMass`, `createServerCell/removeServerCell`, 10 passos novos, `getOwnVehiclePhysics`; depois entradas invalidas, `removeVehicle`, `destroyWorld`.
Exports exercitados: 34 dos 98 (inclui `setVehicleParams` e `setOwnVehiclePhysics` so com id inexistente). Nao exercitados: ragdoll, balistica, constraints, `deactivateChunkMap`, `scrollChunkMap`, malhas de veiculo reais.
Formato: linhas `step=N`/`post=N` com floats a 9 casas; `compare_float.py` imprime a maior diferenca por passo (`--table`), reprova se o passo 120 passar de 1e-3 ou se algum inteiro diferir, e marca `INVESTIGAR` passos intermediarios acima do limite.

## Stub necessario (nao e bug de `native/`)
Durante `stepSimulation` o nativo chama de volta o static do jogo `Bullet.updatePhysicsForLevelIfNeeded(wx,wy,nivel)` (ate 9 chunks por subpasso), que usa `IsoWorld.instance.currentCell.getChunk` e dava NPE (5400 em 120 passos, nas duas arquiteturas; o nativo faz `ExceptionDescribe` + `ExceptionClear` e segue, mas encheu o stderr e levou o x64 a ~35 s). O harness coloca em `currentCell` um `IsoCell` alocado por `Unsafe` com `IsoChunkMap.ignore=true` (`getChunk` devolve null, "chunk nao carregado"). De passagem, isso mostra que upcalls JNI de dentro de `stepSimulation` e excecao Java pendente voltando ao nativo funcionam em ARM64.

## Itens de risco pedidos
- **Threads/SIMD:** a dylib nao importa funcao de thread (so `std::recursive_mutex`, do log); o mundo e single-thread (sem `btITaskScheduler`). Usa NEON/FMA no ARM64 (milhares de `fmadd`/`fmla`), mas o resultado ficou identico ao x64 em todos os floats. Nao ha divergencia numerica nem logica a explicar neste cenario.
- **Funcao DWARF sem LSDA:** e `btConvexHullInternal::findEdgeForCoplanarFaces` (`btConvexHullComputer`). O cubo de `definePhysicsMesh` e o caminho esperado, sem excecao, entao o unwinder nao foi atravessado. Nao instrumentado: nao ha contador de chamadas.
- **`memset_pattern16`:** unico chamador e `cullPoints2` (box-box, `btBoxBoxDetector`); o veiculo (caixa) bate no bloco `Solid` (caixa), mas nao ha contador que prove a chamada.
- **`__chkstk_darwin`:** nenhuma chamada na dylib (nao ha stub).
- **`printf`/`vsnprintf`:** `vsnprintf` so em `PZDebugLog::TryFormatTextV/AllocateBufferForFormatTextV`; `printf`/`puts` so em `CProfileManager::dumpRecursive` (perfil, nao chamado). Todos os formatos da dylib usam `%d`, `%s`, `%f` (nenhum `%ld`). O log nativo vai para `DebugLog.nativeLog` (Java) e fica **mudo no harness** (nem `nativeLog("General",..)` direto imprime sem o sistema de log do jogo), entao nao ha saida para conferir. As chamadas com id inexistente passam pelo caminho de log ("Vehicle not found. VehicleId:%d") sem falha, mas o texto formatado nao foi observado. `%ld` fica coberto pelo ctest da Tarefa 2, nao por esta dylib.

## Tempos (`reports/native-bullet-time-<arch>.txt`)
| Rotina | x64 (media / mediana) | ARM64 (media / mediana) |
|---|---|---|
| `step50`: `controlVehicle` + 50 x `stepSimulation(0.01f)` com o veiculo dirigindo, 20 medidas (3 de aquecimento) | 1139.0 us / 1015.6 us | 494.0 us / 461.4 us |
`chk=1.08` igual nos dois. Cenario inteiro de 120 passos (inclui 49 chunks): x64 ~690 ms, ARM64 ~240 ms. Mesma ressalva: o JRE x64 roda emulado nesta maquina e as JVMs sao 25.0.1 x 25.0.5; variacao entre execucoes ate ~15% (x64 `step50` mediu 1078, 1091, 1139 us). Os maximos sao picos isolados.

## O que a validacao nao cobre (Bullet)
- Ragdoll/personagem real: exige `RagdollScript` (listas preenchidas por `ScriptManager`), esqueleto e poses de modelo, nao inventaveis sem classes do jogo. Nao rodam `initializeRagdoll*`, `addRagdoll`, `simulateRagdoll*`, `define/setRagdoll*`, balistica (~40 exports), constraints, `drawDebug*`. O "personagem" do cenario e so a lista de jogadores (comando 12) e um objeto fisico.
- Malhas reais: so um cubo; nenhuma `PhysicsShape` de veiculo nem modelo real.
- Mundo: um nivel, um chunk-map, sem escadas, arvores ou paredes finas; `setVehicleParams` com parametros validos e `scrollChunkMap*` nao foram chamados; `GameServer.server = true` nao testado.
- `updatePhysicsForLevelIfNeeded` tem stub que devolve "sem chunk"; a carga de niveis sob demanda do jogo nao foi exercitada.
- Um unico veiculo; sem colisao veiculo x veiculo, sem reboque (constraints), sem a thread principal do jogo (aqui e uma thread do harness).
- Cobertura numerica: um cenario; ARM64 igual ao x64 nele, o que nao garante igualdade em cenarios mais caoticos (muitos corpos, contatos profundos).
- Log nativo (`vsnprintf`) nao observavel.
