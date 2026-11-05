# M3 - Resultado (Tarefa 4: primeira execucao ate o menu)

## Veredito
Menu principal alcancado na JVM ARM64, sem nenhuma correcao no jogo alem do que as Tarefas 1-3 ja tinham entregue.
Nenhuma falha de importacao, `UnsatisfiedLinkError` ou `hs_err` na subida.

- Marcador do menu (igual ao x64 em `%USERPROFILE%/Zomboid\console.txt`): `STATE: exit zombie.gameStates.TermsOfServiceState`
  (depois dele o `MainScreenState` assume; `exit ...MainScreenState` so aparece quando se inicia um jogo).
- Tempo do launch ate o marcador: 65 s (corrida medida; uma corrida anterior chegou ao marcador e a um mundo carregado em < 4 min).
- RAM no menu (+5 s depois do marcador): working set 2643 MB (pico 3126 MB), privado 1715 MB. `-Xmx3072m`.
- GL (lido pelo proprio jogo em `cache/console.txt`): `GraphicsCard: Microsoft Corporation D3D12 (Qualcomm(R) Adreno(TM) 680 GPU)`
  (GL_VENDOR = Microsoft Corporation, GL_RENDERER = D3D12 (Qualcomm(R) Adreno(TM) 680 GPU)), `OpenGL version: 4.1 (Compatibility Profile) Mesa 26.3.0-devel (git-e3fc7749c6)` (GL_VERSION). Contexto criado pelo opengl32/GLon12 ARM64.
- Pureza: 116 modulos carregados no `java.exe` (PID 18180), todos `AA64` (cabecalho PE lido de cada arquivo); nenhum modulo vindo da pasta do jogo; as 23 DLLs de `dist\` sao ARM64.

## Alteracoes desta tarefa
1. `native/darwin/dw_fs.c`: `dw_realpath_extsn` com buffer do chamador agora devolve NULL com `errno = ENAMETOOLONG` quando a saida (com NUL) excede 1024 bytes. Teste em `tests/darwin/test_libc.c` (cadeia de diretorios com prefixo `\?\` de ~1030 bytes; limpa depois; buffer NULL ainda aloca o necessario).
2. `launcher/run-game.ps1`: adicionado `-XX:ErrorFile=<logs>\hs_err_%p.log`.
3. `dist/` reconstruido: 23 DLLs + `manifest.json`.

## Falhas encontradas
Nenhuma na subida ate o menu (nenhum agente Java, patch de bink/ShaderUnit/MainScreenState ou override de stub foi necessario).

Mensagens nao fatais no log (mesmas do x64, ou sem efeito no menu):
- `AngelCodeFont failed to load page 0 texture ...mainfont_0.png`: aparece antes do carregamento de texturas; as fontes funcionam depois. Nao investigado.
- `require(...) failed` em Lua(Vanilla), `Sanitizing container name`, `Could not find icon`: avisos do proprio jogo, presentes tambem no log x64.

### Observacao fora do criterio (para o M4)
Na primeira corrida a janela ficou aberta ate o timeout de 300 s e o jogo foi iniciado a partir do menu com um save de teste (carga de 79 s).
Ao renderizar o mundo ocorre, repetidamente:
`TextureFBO.initInternal > glCheckFramebufferStatus = GL_FRAMEBUFFER_UNSUPPORTED` -> `RuntimeException: Could not create FBO!`
(`FBORenderChunk.init` / `FBORenderChunkManager.renderThreadChunkStart`). Nao e o menu; nao foi corrigido (nao ha tentativa de correcao). Candidato para o M4: formato/anexos do FBO no GLon12/Mesa 4.1 (o Zomdroid tambem tem tratamento proprio de FBO/ES).

## Stubs chamados (primeira chamada por DLL, `logs/stub-*.log`)
Ate o menu (corrida medida): `ZNetNoSteam64`: `ZNet_init`, `ZNet_setLogLevel`.

Corrida longa (menu + jogo carregado + render do mundo):
- ZNetNoSteam64: RakVoice_GetBufferSizeBytes, RakNetPeerInterface_GetClientOwnerSteamID, RakNetPeerInterface_GetConnectionType, PortMapper__1get_1external_1address, RakVoice_CloseAllChannels, RakVoice_GetChannelStatistics, RakVoice_GetMaxDistance, ZNet_init, ZNet_setLogLevel
- ZNetJNI64: RakVoice_GetBufferSizeBytes, SteamFriends_GetFriendByIndex, RakNetPeerInterface_GetConnectionType, PortMapper__1get_1external_1address, RakVoice_CloseAllChannels, RakVoice_GetChannelStatistics, RakVoice_GetMaxDistance
- fmodintegration64: FMOD_ChannelGroup_AddDSP, FMOD_Channel_GetAudibility, FMOD_Channel_GetPosition, FMOD_Channel_IsPlaying, FMOD_Studio_EventDescription_GetParameterLabelByIndex, FMOD_Studio_EventInstance_SetPaused
- imgui-java64: ImFontAtlas_nInit, ImGui_nInitJni, ImGuiPlatformIO_nInit, ImGui_nInitInputTextData, ImGui_setAssertCallback, ImDrawIdx sizeOf, ImGui_calcItemWidth, ImGuiOnceUponAFrame_get, ImGui_nCreateContext, ImGui_getClipboardText, ImFontConfig_getEllipsisChar, ImGui_getTime
  (imgui chamada em caminho vivo apenas ao iniciar o jogo/debug; sem erro observado.)
- bink64: nenhum.

## Testes
`ctest`: 68/68. `python -m unittest discover -s tests -t .`: 58 testes OK.
