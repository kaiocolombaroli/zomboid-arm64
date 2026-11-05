# Spike 2 - nativos LWJGL arm64

## Fatos
- O jar do jogo (`projectzomboid.jar`) so contem `.sha1`/`.git` em `META-INF/windows/{x64,arm64,x86}/org/lwjgl/...`, nao as DLLs em si. Modulos listados em x64: lwjgl, glfw, opengl (lwjgl_opengl), jemalloc, stb (lwjgl_stb), tinyfd (lwjgl_tinyfd).
- Os 6 artefatos `*-3.4.1-natives-windows-arm64.jar` existem no Maven Central. SHA-1 de cada jar confere com o `.sha1` publicado. Baixados em `_downloads/lwjgl-3.4.1/`.
- O SHA-1 de cada DLL arm64 extraida bate com o `.sha1` em `META-INF/windows/arm64` do jar do jogo (6/6): lwjgl a84a1eb7..., glfw 62391d8e..., opengl 6771500b..., jemalloc 2ef8ee62..., stb 21e97871..., tinyfd c8f29e2a....
- `pe_arch.py natives-arm64`: 6 DLLs, todas arm64.
- `run-arm64.ps1` agora passa `-Dorg.lwjgl.librarypath=<natives-arm64>`. Execucao unica (120 s): saiu com codigo 1 em segundos, sem janela e sem contexto GL.
- Falha literal: `java.lang.UnsatisfiedLinkError: C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid\PZBullet64.dll: Can't load AMD 64-bit .dll on a ARM 64-bit platform` em `zombie.core.physics.Bullet.loadLibrary(Bullet.java:82)` <- `MainScreenState.main(MainScreenState.java:221)`.
- Sonda `reports/load-probe.txt`: 13/13 DLLs do jogo falham com o mesmo erro "Can't load AMD 64-bit .dll on a ARM 64-bit platform".

## Hipoteses (nao verificadas)
- A falha do lwjgl.dll do spike 1 foi superada; o jogo avanca ate as DLLs proprias, que sao todas x64. Nao se sabe se o LWJGL/GL arm64 funcionaria depois (o jogo nunca chegou a criar janela).
- Sem versoes arm64 de PZBullet, RakNet, FMOD etc., o jogo nao roda no JVM arm64 nativo; caminhos possiveis (JVM x64 sob emulacao, recompilar/substituir libs) estao fora deste spike.
- O `-Djava.awt.headless=true` vindo do json do jogo pode importar mais adiante; nao testado.
