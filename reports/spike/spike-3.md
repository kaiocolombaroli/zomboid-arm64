# Spike 3 - Benchmark de CPU: JVM ARM64 nativa vs x64 emulada (Surface Pro X)

## Setup (fatos)
- A: Zulu 25.0.1 x64 (jre64 do jogo, emulada). B: Microsoft OpenJDK 25.0.5 ARM64 (jdk-25-arm64).
- Opcoes iguais: `-Xms2g -Xmx2g -XX:+UseZGC` (ProjectZomboid64.json usa ZGC no Windows >= 10.0.17134, G1 so no 6.1; o -Xmx3072m do jogo foi trocado por 2g conforme pedido).
- Bench.java compilado com `javac --release 25` do JDK ARM64; um thread; ordem A,B,A,B; nada em paralelo.
- Energia: bateria 100%, na tomada (BatteryStatus=2). Nao foi medida temperatura/clock.
- Desvio do plano: aquecimento 5 s e 3 medicoes de 6 s por carga (em vez de 10 s/10 s) para caber em 15 min. 2 rodadas x 3 medicoes = 6 amostras por JVM/carga.
- Bruto: `bench/results.csv` (ops/s; cada "operacao" e definida por carga, comparavel so entre JVMs).

## Resultados (mediana de 6 amostras, ops/s)
| carga | A x64 emul. | B ARM64 | B/A | dispersao A / B (max-min/mediana) |
|---|---|---|---|---|
| interpreter | 493.9M | 983.0M | 1.99 | 4.7% / 0.4% |
| objects | 53.5M | 108.1M | 2.02 | 17.3% / 12.9% |
| hashmap | 20.6M | 18.2M | 0.89 | 3.2% / 21.1% |
| floatmath | 17.9M | 40.6M | 2.27 | 4.7% / 7.7% |
| grid | 160.2M | 194.0M | 1.21 | 2.2% / 1.0% |

Medianas por rodada (M ops/s), rodada 1 / 2: B objects 110.3/103.5, B hashmap 19.3/17.2 (maior variacao entre rodadas); demais diferem <4%.

Inicio (`java -version`, 5x, ms): A 422,426,417,397,404 (mediana 417); B 280,255,250,252,271 (mediana 255) -> B ~39% mais rapido.

## Fatos
- Em cargas de CPU/JIT puras (interpretador, objetos/virtuais, float/sqrt/atan2) a JVM ARM64 foi ~2.0-2.3x mais rapida.
- Grid (acesso a array, limitado por memoria/cache) ganho menor: 1.21x.
- HashMap<String,Object> foi ~11% mais lento no ARM64 na mediana, com dispersao alta (21%) em B.
- Versoes diferem (Zulu 25.0.1 vs MS 25.0.5): parte da diferenca pode vir do build/JIT, nao so da emulacao.

## Hipoteses (nao verificadas)
- O ganho ~2x vem da ausencia da traducao x64->ARM64 (emulacao) no codigo JIT.
- HashMap: pressao de memoria/GC (ZGC) ou nucleos little do big.LITTLE podem explicar a variancia e o resultado; precisaria de repeticao com afinidade de CPU fixa e mais rodadas.
- A carga objects tambem tem variancia alta em ambas (agendador big.LITTLE / alocacao).

## Limites
Este benchmark mede apenas CPU/JIT da JVM em um thread. Nao mede GPU, nem as DLLs nativas do jogo (win64/, LWJGL, Steam), nem multithread, nem o desempenho real do Project Zomboid. Poucas rodadas (2), sem controle de termica/clock; os resultados sao indicativos.
