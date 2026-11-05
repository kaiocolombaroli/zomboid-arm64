# Resultado do M5

Medição de 2026-11-04, ARM64, mesmo `cache/`, uma sessão de cada, com `fboRenderChunk=false`.

| Medida | Sem som | Com som |
|---|---|---|
| Tempo no mundo | 131 s | 357 s |
| Frames no mundo | 4.523 | 14.933 |
| FPS médio no mundo | 34,5 | 41,8 |
| RAM livre mínima no mundo | 432 MB | 54 MB |

Fonte: CSV de FPS, a partir da saída de `GameLoadingState`.

## Critérios

- Inicialização do FMOD sem erro: cumprido.
- Música, mundo, passos, zumbis, combate, pausa e fechamento: confirmados pelo usuário.
- Queda de FPS de no máximo 15%: cumprido. A sessão com som deu mais FPS, então a diferença reflete trajeto e cena, não custo real do som.
- 10 minutos: não comprovado por log. O CSV registra 357 s no mundo com som.

## Limites

- RAM livre chegou a 54 MB no mundo com som, e a 6 MB na sessão inteira.
- Log de `GameThread exited` ausente na sessão com som; o fechamento foi relatado pelo usuário.
- Voz e multiplayer não foram testados.
