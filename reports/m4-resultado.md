# Resultado do M4

Medição de 2026-11-04, uma sessão de cada versão, com o mesmo `cache/`, `texture2x=true`, `textureCompression=true`, 1152x768.

| Medida | x64 emulada | ARM64 | Ganho |
|---|---|---|---|
| Tempo no mundo | 77,3 s | 54,5 s | |
| Frames no mundo | 1.122 | 2.360 | |
| FPS médio no mundo | 14,5 | 43,3 | 3,0x |
| Do início ao fim dos termos | 79 s | 55 s | 1,4x |
| Carregar o mundo | 138 s | 93 s | 1,5x |

Fonte: marcas `STATE` e contador `f:` de `cache/Logs/`. O CSV de FPS inclui menu e carregamento, então a razão 2,67x de `fps_report.py` não é a do mundo.

## Decisão

A regra de 1,3x foi atendida. O M5 está liberado.

## Limites

- Uma sessão curta de cada versão, com trajetos não idênticos.
- Limite de 60 FPS ativo; o ARM64 pode ter sido limitado.
- RAM livre mínima de 10 MB no ARM64, durante o carregamento.
- O bug visual existe nas duas versões, segundo o usuário; causa desconhecida.
