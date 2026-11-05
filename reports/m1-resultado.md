# M1 - Resultado: libLighting.dylib (ARM64) x Lighting64.dll (x64)

## Resultado
`python tests/harness/compare.py reports/lighting-x64.txt reports/lighting-arm64.txt` -> `OK: 227 linhas comparadas, 0 divergencias`.
Os dois arquivos sao identicos byte a byte. Saida deterministica: 3 execucoes seguidas em cada arquitetura geram o mesmo hash
(x64 e ARM64 com o mesmo SHA-256).

## Bug encontrado e corrigido em native/ (antes da prova)
Primeira rodada no ARM64: sem crash, mas saida diferente do x64 e variavel entre execucoes
(`getVisibleRoomCount` devolvia 2/3 em vez de 0, `getSquareLighting[4]` = 0 em vez de 10, uma das luzes as vezes nao aparecia).
Causa: `native/bridge/bridge_main.c` passava `g_table[i].letters + 2` a `macho_jni_bridge`, tirando as letras `pp`
(JNIEnv*, jclass). `macho_jni_bridge` conta registradores a partir da lista completa (os testes em `tests/loader/test_bridge.c`
ja passam `pp...`), entao com o prefixo removido ele errava quais argumentos iam para a pilha e quais registradores estendia:
`squareSet` (3 args na pilha: long, int, boolean) recebia lixo da pilha, `uxtb` caia em `w0` (JNIEnv) etc.
Correcao: passar `g_table[i].letters` inteiro e `"pp"` para `JNI_OnLoad`. Nenhum teste ou criterio foi alterado;
`ctest` continua 16/16. `dist/Lighting64.dll` reconstruida (arm64 segundo `pe_arch.py`).

## Sequencia usada (thread "Lighting Thread", sem iniciar o jogo)
Metodos reais de `zombie.iso.LightingJNI` (todos existem com os nomes do plano; a sequencia reproduz `LightingJNI.update()`/`updateChunk()` do bytecode):
1. `LightingJNI.init()` (real, ver abaixo).
2. `addLight` x2 (como `checkLights()`): (1, x=+18, y=+18, z=32, raio 10, 1.0/0.8/0.5, predio -1, ativa) e (2, +26,+14, raio 8, 0.3/0.6/1.0).
3. Quadro principal (`mainFrame`): `stateBeginUpdate(0, 1000, 800, 5, 5)` (janela de 5x5 chunks), `playerSet`, `stateEndFrame`,
   para cada chunk: `chunkBeginUpdate(cx,cy,32,33)`, `chunkLevelBeginUpdate(32)`, 64x [`squareBeginUpdate`, `squareSet`, `squareSetLightTransmission` (20 floats), `squareEndUpdate`],
   `chunkLevelEndUpdate`, nivel 33 com 64x `squareSetNull`, `chunkEndUpdate`; depois `chunkLightingDone` por chunk e `stateEndUpdate`.
   Terreno: parede em x%16==12 com abertura em y%16==8; quadrados "vistos" quando (x+y)%3==0; nivel z=0 (+32).
4. `DoLightingUpdateNew(tempo fixo, bool)` alternado com quadros principais (`WaitingForMain`) ate o snapshot ficar estavel por 50 ciclos de 20 ms
   (ambas as JVMs convergem em 2 ciclos). Tempo passado ao nativo e sintetico (`1e9 + n*16ms`), nunca `nanoTime`.
5. Leituras: para 7x7 quadrados fixos (x 12..26, y 14..26, passo 2): `getSquareDirty/getSeen/getCanSee/getCouldSee/getDarkMulti/getTargetDarkMulti`,
   `getVertLight` (8 vertices), `getLightInfo` (3 canais), `getSquareLighting` (int[49]), alem de `stateUpdateCounter` e `getVisibleRoomCount`. Floats com 6 casas.
   `stateUpdateCounter` fica em 0 e `getVisibleRoomCount` em 0 (sem salas): o quadro e dados de sala nao foram exercitados.

## Escolhas para `init`
`LightingJNI.init()` e chamado como esta no jar: so faz `System.loadLibrary("Lighting64")` (via `LightingJNI`, entao o mesmo class loader) e `configure(0.005f)`.
O inicializador estatico so precisa de `ColorInfo`, `IsoDirections`, `ArrayList`, `Vector2` (sem dependencia do jogo). Nenhum objeto do jogo foi necessario;
nada de reflexao. `--enable-native-access=ALL-UNNAMED` evita o aviso do JDK 25. Flags: `-Djava.library.path=.` com cwd = pasta do jogo (x64, so leitura) e
`-Djava.library.path=<repo>\dist` (ARM64; o script recusa caminho dentro da pasta do jogo).

## Determinismo
- Fonte inicial de variacao no ARM64: o bug de ponte acima. Corrigido, 3/3 execucoes iguais.
- Achado no x64 de referencia: preencher `squareSetLightTransmission` com valores nao-zero (varios quadrados) torna a saida do proprio x64
  nao-deterministica de uma execucao para outra (erro de 1-3 unidades de 255 nos quadrados afetados) e a rotina ~14x mais lenta. Origem nao
  identificada (provavel leitura de memoria nao inicializada da propria lib, ou dependencia de ordem). O jogo envia zeros por padrao (sem cortina/porta/janela),
  entao o harness envia zeros; o caminho nao-zero fica atras de `LH_TRANSMISSION=1` e NAO e comparado.

## Tempos (`DoLightingUpdateNew`, 200 de aquecimento + 1000 medidas, so a chamada nativa e cronometrada; um quadro principal entre chamadas, fora do tempo)
| modo | x64 media / mediana | ARM64 media / mediana |
|---|---|---|
| steady (so quadro principal) | 1120,3 us / 1104,8 us | 407,0 us / 399,1 us |
| relight (reenvia os 25 chunks antes de cada chamada) | 2176,3 us / 2136,0 us | 829,1 us / 821,6 us |
Dado informativo. Esta maquina e ARM64: a JVM x64 roda sob emulacao, entao o x64 e mais lento; nao e uma comparacao de codigo equivalente em hardware x64.

## O que a validacao NAO cobre
- Um unico cenario (jogador 0, 1 nivel + 1 vazio, 2 luzes, uma parede); sem salas/predios (`addRoomLight`, `getVisibleRooms` = 0), sem lanternas (`updateTorch`, que tem pilha + extensao),
  sem `squareAddCurtain/Door/Window/Thumpable`, sem `scroll*/teleport`, sem varios jogadores nem varios niveis.
  Nao-coberto = assinaturas de ponte ainda nao exercitadas em tempo de execucao (`addRoomLight`, `updateTorch`).
- Transmissao nao-zero (ver acima).
- Nao usa `LightingJNI.update()/updateChunk()` com objetos reais do jogo (`IsoChunk`, `IsoGridSquare`), so as chamadas nativas.
- Alta concorrencia: o harness chama tudo de uma thread; o jogo alterna a thread de iluminacao e a principal.
- Comparar so entre x64 emulado e ARM64 nativo nesta maquina; a tolerancia 1e-4 nao foi necessaria (diferenca 0).

## Complemento: comparacao por banda com `LH_TRANSMISSION=1` (20 floats nao-zero chegam pela pilha?)
A comparacao oficial (`compare.py`, zeros) nao e alterada. Complemento: 5 execucoes por arquitetura com `LH_TRANSMISSION=1` em `reports/transmission/{x64,arm64}-run1..5.txt`
(+ `-time`), analisadas por `tests/harness/compare_band.py` (banda min/max por token; passa se a mediana ARM64 estiver a <= 4/255 por canal de cor, ou 2% relativo nos demais numeros, da mediana x64;
linhas `info` sao cor RGB float e usam 4/255).
- Resultado: OK. 227 linhas; variacao propria em 60 tokens / 9 linhas, tanto no x64 quanto no ARM64 (so os quadrados proximos da transmissao nao-zero); pior divergencia entre medianas: 3/255 (cor), 0% (demais).
  Nota: a DLL ARM64 tambem varia entre execucoes, nao so a x64. Origem da variacao nao investigada alem do que ja estava no relatorio.
- Sensibilidade (execucoes ARM64 unicas com argumentos trocados, `LH_SWAP_TRANSMISSION`, so para este teste):
  - `=1`: troca floats 0 e 3 (0.6 <-> 1.0, ambos nos primeiros registradores): compare_band PASSA (pior 4/255, 9 linhas diferem do run normal). NAO detecta: o efeito fica dentro da tolerancia/ruido.
  - `=2`: troca floats 3 e 19 (19 vai pela pilha): compare_band FALHA (57 tokens fora; ex.: canal 4-5/255 vs 0). O teste enxerga erro na passagem pela pilha, mas nao troca entre os primeiros floats.
- Limite: so os floats 0-3 sao nao-zero no cenario; os floats 4-19 (toda a pilha) so sao verificados por troca com um desses (`=2`).
