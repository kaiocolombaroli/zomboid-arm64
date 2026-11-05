# Spike 1 - Project Zomboid 42.21 em JVM ARM64 (Surface Pro X, Win11 ARM)

Data: 2026-11-04. Jogo: C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid. JVM do spike: jdk-25-arm64 (OS_ARCH aarch64).
Nada foi escrito na pasta do jogo nem em %USERPROFILE%/Zomboid (cache em spike-java-arm/cache).

## Inventario (fatos)
- `python -I pe_arch.py <jogo>` -> reports/inventory.txt. Resumo: **x64: 117, arm64: 0, arm64ec: 0, x86: 0** (inclui jre64 e ProjectZomboid64.exe).
- DLLs da raiz (todas x64): Lighting64, PZBullet64, PZClipper64, PZPathFind64, PZPopMan64, RakNet64, ZNetJNI64, ZNetNoSteam64, bink2w64, bink64, fmod, fmodintegration64, fmodstudio, imgui-java64, jassimp64, jnidispatch, steam_api64 + ProjectZomboid64.exe.
- jre64/bin/java.exe: x64. Java embutido: Azul Zulu 25.30+17-CA, 25.0.1+8-LTS (OS_ARCH x86_64).
- Nao existe pasta `win64/` na instalacao (o vmArg `-Djava.library.path=win64/;.` aponta para o inexistente; as DLLs estao na raiz, coberta pelo `.`).
- ProjectZomboid64.json: mainClass `zombie/gameStates/MainScreenState`; classpath `.`, `projectzomboid.jar`; vmArgs: `-Djava.awt.headless=true`, `--enable-native-access=ALL-UNNAMED`, `--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`, `-Xmx3072m`, `-Dzomboid.steam=1`, `-Dzomboid.znetlog=1`, `-Djava.library.path=win64/;.`, `-XX:-CreateCoredumpOnCrash`, `-XX:-OmitStackTraceInFastThrow`. (Bloco "windows" com UseG1GC/UseZGC por versao do SO; o run-arm64.ps1 ignora.) O JSON nao tinha `-nosteam`.

## Bibliotecas Java (fatos)
Classpath = so `projectzomboid.jar` (65,7 MB, fat jar multi-release, 26140 entradas; pacotes zombie 5365, org 8293, com 5603...). Nao ha jars separados; as libs estao embutidas.
| Lib | Versao aparente | Nativos no jar | Windows arm64? |
|---|---|---|---|
| LWJGL (core, glfw, opengl, jemalloc, stb, tinyfd) | 3.x com FFM em META-INF/versions/25 (provavelmente 3.4.x; versao exata nao lida) | `windows/x64/org/lwjgl/...` (lwjgl.dll, glfw.dll, lwjgl_opengl.dll, jemalloc.dll, lwjgl_stb.dll, lwjgl_tinyfd.dll) | **NAO** embutido. Existem apenas arquivos `META-INF/windows/arm64/.../glfw.dll.sha1` (etc., metadados), sem a DLL |
| JNA | 5.18.1 | `com/sun/jna/win32-aarch64/jnidispatch.dll` | **SIM** (tambem win32-x86-64, win32-x86) |
| sqlite-jdbc | 3.48.0.0 | `org/sqlite/native/Windows/aarch64/sqlitejdbc.dll` | **SIM** (+armv7, x86, x86_64) |
| imgui-java | 1.86.11 | `imgui-java64.dll` solta na raiz (x64) | NAO |
| oshi-core-java25 | 6.9.3 | sem nativos (usa FFM/JNA) | n/a |
| jassimp | - | `jassimp64.dll` na raiz (x64) | NAO |
| fmod (javafmodJNI) | - | fmod.dll, fmodstudio.dll, fmodintegration64.dll (x64) | NAO |
Outras libs puras Java embutidas: guava, jackson, commons-*, log4j-api, slf4j, vavr, okhttp3/okio, kotlin, javacord, bouncycastle, luaj, joml, json, zxing, prometheus.

## Nativos (fatos) - reports/natives.txt, scan_natives.py
- 23702 classes lidas, 0 erros de parse. 304 classes com ACC_NATIVE, 7798 metodos native no total (a maior parte em LWJGL opengl/system, imgui, fmod).
- Metodos native do jogo (zombie/ + fmod/ + jassimp): fmod/javafmodJNI 445; zombie/core/physics/Bullet 93 (+PhysicsDebugRenderer 5); zombie/core/znet (ZNet, Steam*, ServerBrowser, PortMapper, ...) 88; zombie/core/raknet (RakNetPeerInterface 30, RakVoice 22) 52; zombie/iso/LightingJNI 50; zombie/popman (ZombiePopulationManager 28, MPDebugInfo 8, Renderer 5, DebugCommands 1) 42; zombie/MapCollisionData 17; zombie/vehicles Clipper 13 + ClipperOffset 6; zombie/pathfind/nativeCode PathfindNative 11 + Renderer 2; zombie/core/textures/VideoTexture 11 (bink); jassimp/Jassimp 10; zombie/jsig 1; zombie/pzexe 1.
- Onde cada lib e carregada (strings do pool de constantes, `loadLibrary`):
  - Lighting64 -> zombie.iso.LightingJNI e zombie.GameWindow
  - PZPopMan64 -> zombie.popman.ZombiePopulationManager
  - PZPathFind64 -> zombie.pathfind.nativeCode.PathfindNative
  - PZClipper64 -> zombie.vehicles.Clipper
  - PZBullet64 -> zombie.core.physics.Bullet (nome da DLL nao aparece literal; montado em runtime)
  - RakNet64, ZNetJNI64, ZNetNoSteam64, steam_api64 -> zombie.core.znet.SteamUtils (RakNet64/ZNetNoSteam64 tambem em zombie.network.FakeClientManager)
  - fmodintegration64, fmodstudio -> fmod.javafmodJNI; bink64/bink2w64 -> VideoTexture; jassimp64 -> JAssImpImporter$LibraryLoader / jassimp.Jassimp; imgui-java64 -> imgui.ImGui
  - fmodintegration64 e demais sao carregadas por nome, via java.library.path
- Checagem de arquitetura: `os.arch` e lido em `zombie.gameStates.MainScreenState` (nao decodifiquei a logica), `imgui.ImGui`, LWJGL `Platform$Architecture`/`Library`, JNA `Platform`, sqlite `OSInfo`. As DLLs nativas do jogo sao carregadas so por `loadLibrary("<nome>64")`, sem caminho alternativo por arquitetura. As classes de jogo checam apenas `os.name`.
- `Pathfind.UseNativeCode` existe (DebugOptions, chaves `pathfindUseNativeCode`, `PathfindNative.checkUseNativeCode` em IngameState/IsoWorld/PathfindNative): indica caminho Java alternativo para pathfinding. Nao ha opcao equivalente evidente para Lighting, PopMan, Clipper, Bullet, RakNet.

## Execucao (fatos)
- run-arm64.ps1 alterado: argumento `-nosteam` apos a mainClass, parametro `-TimeoutSec` (120), lancamento via Start-Process com stdout/stderr em arquivos e kill so do PID iniciado. `$DropArgs` nao foi preciso.
- 1 execucao (logs/run-20261104-100716.log; copia UTF-8 em reports/run1-utf8.log). A JVM ARM64 aceitou todas as flags do JSON (incl. -XX:-CreateCoredumpOnCrash, --add-exports). O processo terminou sozinho com codigo de saida 1 em poucos segundos. Nao abriu janela nem criou contexto GL.
- Nao fiz 2a/3a execucao: a falha e na carga do primeiro nativo e nao e problema de flag.

## Primeira falha (fatos)
```
[LWJGL] Failed to load a library. Possible solutions:
	a) Add the directory that contains the shared library to -Djava.library.path or -Dorg.lwjgl.librarypath.
	b) Add the JAR that contains the shared library to the classpath.
[LWJGL] Enable debug mode with -Dorg.lwjgl.util.Debug=true for better diagnostics.
[LWJGL] Enable the SharedLibraryLoader debug mode with -Dorg.lwjgl.util.DebugLoader=true for better diagnostics.
Exception in thread "main" java.lang.UnsatisfiedLinkError: Failed to locate library: lwjgl.dll
	at org.lwjgl.system.Library.loadSystem(Library.java:177)
	at org.lwjgl.system.Library.loadSystem(Library.java:67)
	at org.lwjgl.system.Library.<clinit>(Library.java:55)
	at org.lwjgl.glfw.GLFW.<clinit>(GLFW.java:24)
	at org.lwjglx.opengl.Display.init(Display.java:174)
	at zombie.gameStates.MainScreenState.main(MainScreenState.java:147)
```
Codigo de saida: 1.

## Hipoteses (nao verificadas)
- LWJGL detecta arch aarch64 e procura `windows/arm64/org/lwjgl/lwjgl.dll` no jar, que so tem `windows/x64/...`; por isso "Failed to locate lwjgl.dll". (Poderia ser confirmado com -Dorg.lwjgl.util.DebugLoader=true; nao rodado.)
- Mesmo com LWJGL arm64 (build oficial existe para windows/arm64), os proximos bloqueios seriam as DLLs x64 do jogo: JVM ARM64 nao carrega DLL x64 in-process (so arm64/arm64ec), entao Lighting64, PZPopMan64, PZBullet64, PZClipper64, PZPathFind64 (tem fallback Java), RakNet64, ZNetJNI64, fmod*, bink*, imgui-java64, jassimp64 exigiriam rebuild/substitutos. Sem codigo-fonte dessas DLLs, so alguns tem alternativa (pathfind em Java; fmod/RakNet/bink/imgui sao produtos de terceiros com SDK arm64 variavel).
- Alternativa a avaliar depois: JVM x64 emulada (o jre64 do jogo) com todas as DLLs x64 intactas.
