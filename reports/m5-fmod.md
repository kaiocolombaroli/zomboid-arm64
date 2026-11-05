# M5 Tarefa 3 - ponte FMOD (`fmodintegration64.dll` real)

Status: concluida (sem a etapa de ouvir). `ctest` 92/92, `python -m unittest` 76 OK. Nada escrito nas pastas do jogo e do FMOD; sem commit.

## 1. FMOD ARM64 (Tarefa 1, passos 2 a 4)

- Origem: `C:\Program Files (x86)\FMOD SoundSystem\FMOD Studio API Windows\api\core\lib\arm64\fmod.dll` e `...\studio\lib\arm64\fmodstudio.dll`
  (sem `L`), copiadas para `third-party/fmod/` (`third-party/` esta no `.gitignore`).
- `python -I tools/check_fmod.py`:
  ```
  simbolos FMOD_* importados pela dylib: 367
  arquitetura: arm64   versao: 2.3.9 (build 155273)
  jogo (x64): x64   versao: 2.3.9 (build 155273)
  OK
  ```
  Os 367 simbolos `FMOD_*` que `libfmodintegration.dylib` importa estao exportados por `fmod.dll`/`fmodstudio.dll` com o mesmo nome
  (a Apple usa exatamente os mesmos nomes C). A dylib confere `FMOD_VERSION == 0x00020309` em `FMOD_System_Create`: a versao tem de ser 2.3.9.
- Testes: `tests/tools/test_check_fmod.py` (`test_detects_missing`, `test_rejects_x64`, `test_real_dylib_imports_present`).

## 2. Ponte

Arquivos novos: `tools/gen_fmod_shim.py`, `native/fmodshim/{fmodshim.c,fmodshim.h,fmodshim_gen.inc}`, `tests/darwin/test_fmodshim.c`,
`tests/tools/test_gen_fmod_shim.py`, `tests/harness/FmodHarness.java`, fixtures `tests/fixtures/{fmod_stub,fmoddep_test,other_stub,otherdep_test}.c`.
Alterados: `native/loader/macho_loader.{c,h}`, `native/darwin/pzdarwin.def`, `native/bridge/bridge_main.c`, `native/CMakeLists.txt`,
`tests/loader/test_loader.c`, `tests/harness/run-native.ps1`, `tests/tools/test_gen_stub.py`.

### 2.1 Importacoes da dylib
- 367 `FMOD_*` (API C): ponteiro direto. `dw_fmod_resolve("FMOD_...")` devolve o endereco achado por `GetProcAddress` em `JNI_OnLoad`
  (`fmod_shim_init`: usa as DLLs que a JVM ja carregou, senao as da pasta da ponte, senao PATH). Todos os ausentes saem de uma vez numa linha
  (`N simbolo(s) FMOD ausente(s) ...`) e `JNI_OnLoad` devolve `JNI_ERR`. Sem thunks `br`: DLL e dylib usam AAPCS64.
  O gerador confere nos cabecalhos que nenhum desses 367 e variadico, passa estrutura por valor, nem tem argumento de pilha menor que 8 bytes
  (so `FMOD_System_GetRecordDriverInfo` tem 9 inteiros: o 9o e ponteiro, 8 bytes nos dois lados).
- 61 `FMOD::Studio::*` (API C++; o plano dizia 70, a contagem real e 61; `_ZGV...`/`EventInstanceCallback` sao da propria dylib e nao entram):
  cada um vira uma funcao shim `this -> handle C` em `fmodshim_gen.inc`. Nao ha `FMOD::System`/`Channel` C++ na lista.
  O gerador decodifica o simbolo Itanium, compara os tipos com a declaracao de `fmod_studio.hpp` (classe, metodo, `const`, tipo de cada
  parametro) e confere que a funcao C existe em `fmod_studio.h` com o numero de argumentos esperado; qualquer divergencia aborta a geracao.
  Todas as 61 funcoes C alvo existem em `fmodstudio.dll` (testado com as DLLs reais: `fmodshim_resolves_real_dlls`).
- `bool` por valor (`setPaused`, `setMute`, `setParameterBy*`): o shim declara `unsigned int` e usa so o byte baixo (`(x & 0xFF) != 0`) para o `FMOD_BOOL` (int).
  `bool*` de saida (`getPaused`, `getMute`, `hasSustainPoint`): o shim passa um `int` temporario e grava 1 byte 0/1; NULL continua NULL; nada e escrito em erro.
- `FMOD_STUDIO_PARAMETER_ID` (8 bytes) por valor: cabe num registrador nas duas convencoes (testado).
- Enums por valor (`FMOD_STUDIO_STOP_MODE`, `FMOD_STUDIO_EVENT_PROPERTY`): `int` de 4 bytes nos dois lados.

#### Pares C++ -> C (os 61)

| # | Metodo C++ (fmod_studio.hpp) | Funcao C |
|---|---|---|
| 1 | `EventInstance::setCallback(FMOD_STUDIO_EVENT_CALLBACK callback, FMOD_STUDIO_EVENT_CALLBACK_TYPE callbackmask)` | `FMOD_Studio_EventInstance_SetCallback` |
| 2 | `EventInstance::setProperty(FMOD_STUDIO_EVENT_PROPERTY index, float value)` | `FMOD_Studio_EventInstance_SetProperty` |
| 3 | `EventInstance::setUserData(void * userdata)` | `FMOD_Studio_EventInstance_SetUserData` |
| 4 | `EventInstance::set3DAttributes(const FMOD_3D_ATTRIBUTES * attributes)` | `FMOD_Studio_EventInstance_Set3DAttributes` |
| 5 | `EventInstance::setParameterByID(FMOD_STUDIO_PARAMETER_ID id, float value, bool ignoreseekspeed)` | `FMOD_Studio_EventInstance_SetParameterByID` |
| 6 | `EventInstance::setParameterByName(const char * name, float value, bool ignoreseekspeed)` | `FMOD_Studio_EventInstance_SetParameterByName` |
| 7 | `EventInstance::setTimelinePosition(int position)` | `FMOD_Studio_EventInstance_SetTimelinePosition` |
| 8 | `EventInstance::stop(FMOD_STUDIO_STOP_MODE mode)` | `FMOD_Studio_EventInstance_Stop` |
| 9 | `EventInstance::start()` | `FMOD_Studio_EventInstance_Start` |
| 10 | `EventInstance::keyOff()` | `FMOD_Studio_EventInstance_KeyOff` |
| 11 | `EventInstance::release()` | `FMOD_Studio_EventInstance_Release` |
| 12 | `EventInstance::setPaused(bool paused)` | `FMOD_Studio_EventInstance_SetPaused` |
| 13 | `EventInstance::setVolume(float volume)` | `FMOD_Studio_EventInstance_SetVolume` |
| 14 | `EventDescription::loadSampleData()` | `FMOD_Studio_EventDescription_LoadSampleData` |
| 15 | `Bus::stopAllEvents(FMOD_STUDIO_STOP_MODE mode)` | `FMOD_Studio_Bus_StopAllEvents` |
| 16 | `Bus::setMute(bool mute)` | `FMOD_Studio_Bus_SetMute` |
| 17 | `Bus::setPaused(bool paused)` | `FMOD_Studio_Bus_SetPaused` |
| 18 | `Bus::setVolume(float volume)` | `FMOD_Studio_Bus_SetVolume` |
| 19 | `VCA::setVolume(float volume)` | `FMOD_Studio_VCA_SetVolume` |
| 20 | `Bank::loadSampleData()` | `FMOD_Studio_Bank_LoadSampleData` |
| 21 | `System::initialize(int maxchannels, FMOD_STUDIO_INITFLAGS studioflags, FMOD_INITFLAGS flags, void * extradriverdata)` | `FMOD_Studio_System_Initialize` |
| 22 | `System::loadBankFile(const char * filename, FMOD_STUDIO_LOAD_BANK_FLAGS flags, Bank ** bank)` | `FMOD_Studio_System_LoadBankFile` |
| 23 | `System::flushCommands()` | `FMOD_Studio_System_FlushCommands` |
| 24 | `System::setNumListeners(int numlisteners)` | `FMOD_Studio_System_SetNumListeners` |
| 25 | `System::setParameterByID(FMOD_STUDIO_PARAMETER_ID id, float value, bool ignoreseekspeed)` | `FMOD_Studio_System_SetParameterByID` |
| 26 | `System::setParameterByName(const char * name, float value, bool ignoreseekspeed)` | `FMOD_Studio_System_SetParameterByName` |
| 27 | `System::setListenerAttributes(int listener, const FMOD_3D_ATTRIBUTES * attributes, const FMOD_VECTOR * attenuationposition)` | `FMOD_Studio_System_SetListenerAttributes` |
| 28 | `static System::create(System ** system, unsigned int headerversion)` | `FMOD_Studio_System_Create` |
| 29 | `System::update()` | `FMOD_Studio_System_Update` |
| 30 | `EventInstance::getUserData(void ** userdata) const` | `FMOD_Studio_EventInstance_GetUserData` |
| 31 | `EventInstance::getPlaybackState(FMOD_STUDIO_PLAYBACK_STATE * state) const` | `FMOD_Studio_EventInstance_GetPlaybackState` |
| 32 | `EventInstance::getParameterByName(const char * name, float * value, float * finalvalue) const` | `FMOD_Studio_EventInstance_GetParameterByName` |
| 33 | `EventInstance::getTimelinePosition(int * position) const` | `FMOD_Studio_EventInstance_GetTimelinePosition` |
| 34 | `EventInstance::getPaused(bool * paused) const` | `FMOD_Studio_EventInstance_GetPaused` |
| 35 | `EventInstance::getVolume(float * volume, float * finalvolume) const` | `FMOD_Studio_EventInstance_GetVolume` |
| 36 | `EventDescription::createInstance(EventInstance ** instance) const` | `FMOD_Studio_EventDescription_CreateInstance` |
| 37 | `EventDescription::getInstanceList(EventInstance ** array, int capacity, int * count) const` | `FMOD_Studio_EventDescription_GetInstanceList` |
| 38 | `EventDescription::hasSustainPoint(bool * sustainPoint) const` | `FMOD_Studio_EventDescription_HasSustainPoint` |
| 39 | `EventDescription::getInstanceCount(int * count) const` | `FMOD_Studio_EventDescription_GetInstanceCount` |
| 40 | `EventDescription::getParameterDescriptionCount(int * count) const` | `FMOD_Studio_EventDescription_GetParameterDescriptionCount` |
| 41 | `EventDescription::getParameterDescriptionByIndex(int index, FMOD_STUDIO_PARAMETER_DESCRIPTION * parameter) const` | `FMOD_Studio_EventDescription_GetParameterDescriptionByIndex` |
| 42 | `EventDescription::getID(FMOD_GUID * id) const` | `FMOD_Studio_EventDescription_GetID` |
| 43 | `EventDescription::getPath(char * path, int size, int * retrieved) const` | `FMOD_Studio_EventDescription_GetPath` |
| 44 | `EventDescription::getLength(int * length) const` | `FMOD_Studio_EventDescription_GetLength` |
| 45 | `Bus::getMute(bool * mute) const` | `FMOD_Studio_Bus_GetMute` |
| 46 | `Bus::getVolume(float * volume, float * finalvolume) const` | `FMOD_Studio_Bus_GetVolume` |
| 47 | `VCA::getPath(char * path, int size, int * retrieved) const` | `FMOD_Studio_VCA_GetPath` |
| 48 | `VCA::getVolume(float * volume, float * finalvolume) const` | `FMOD_Studio_VCA_GetVolume` |
| 49 | `Bank::getVCAList(VCA ** array, int capacity, int * count) const` | `FMOD_Studio_Bank_GetVCAList` |
| 50 | `Bank::getVCACount(int * count) const` | `FMOD_Studio_Bank_GetVCACount` |
| 51 | `Bank::getEventList(EventDescription ** array, int capacity, int * count) const` | `FMOD_Studio_Bank_GetEventList` |
| 52 | `Bank::getEventCount(int * count) const` | `FMOD_Studio_Bank_GetEventCount` |
| 53 | `System::getBankList(Bank ** array, int capacity, int * count) const` | `FMOD_Studio_System_GetBankList` |
| 54 | `System::getBankCount(int * count) const` | `FMOD_Studio_System_GetBankCount` |
| 55 | `System::getCoreSystem(FMOD::System ** system) const` | `FMOD_Studio_System_GetCoreSystem` |
| 56 | `System::getBufferUsage(FMOD_STUDIO_BUFFER_USAGE * usage) const` | `FMOD_Studio_System_GetBufferUsage` |
| 57 | `System::getParameterDescriptionList(FMOD_STUDIO_PARAMETER_DESCRIPTION * array, int capacity, int * count) const` | `FMOD_Studio_System_GetParameterDescriptionList` |
| 58 | `System::getParameterDescriptionCount(int * count) const` | `FMOD_Studio_System_GetParameterDescriptionCount` |
| 59 | `System::getBus(const char * path, Bus ** bus) const` | `FMOD_Studio_System_GetBus` |
| 60 | `System::getVCA(const char * path, VCA ** vca) const` | `FMOD_Studio_System_GetVCA` |
| 61 | `System::getEvent(const char * path, EventDescription ** event) const` | `FMOD_Studio_System_GetEvent` |


### 2.2 Carregador
- Aceita exatamente `@rpath/libfmod.dylib` e `@rpath/libfmodstudio.dylib` (nada e carregado). Qualquer outra dependencia alem de libc++/libSystem
  continua recusada (`depends on ...`).
- `macho_set_extern_resolver(fn)` (exportada por `pzdarwin.dll`): so quem declarou a dependencia FMOD consulta o resolvedor, depois de `dw_resolve`
  e antes dos exports da propria dylib. A ponte registra `dw_fmod_resolve` antes de `macho_load`.
- Testes: `loader_fmod_dependency_resolved`, `loader_fmod_dependency_needs_resolver`, `loader_other_dependency_rejected` (fixtures Mach-O reais ligados a
  um `libfmod.dylib` de mentira com `ld.lld`).
- `macho_lib_wraps_env(libfmodintegration)` = 1 (exporta `JNIEnv_`): a ponte usa o wrapper (log: "a dylib usa JNI variadica: JNIEnv embrulhado").

### 2.3 Estruturas (sem `long` na API; so `long long`)
`clang -Xclang -fdump-record-layouts-complete` sobre `fmod.h` + `fmod_studio.h`, para `arm64-apple-macos11` e `aarch64-w64-mingw32`: os dois dumps
(62 registros) sao identicos. Tamanhos: `FMOD_ADVANCEDSETTINGS` 104, `FMOD_STUDIO_ADVANCEDSETTINGS` 32, `FMOD_CREATESOUNDEXINFO` 224,
`FMOD_3D_ATTRIBUTES` 48, `FMOD_GUID` 16, `FMOD_STUDIO_PARAMETER_ID` 8, `FMOD_DSP_DESCRIPTION` 216 (teste `test_struct_layouts_equal_apple_and_windows`).
No bytecode da dylib (`llvm-objdump --macho --arch arm64`): `cbSize` de `FMOD_ADVANCEDSETTINGS` = `mov w8,#0x68` (104) com `maxVorbisCodecs` (offset 16) = 64,
`vol0virtualvol` (offset 56) = 0.001f e `randomSeed` (offset 88); `cbsize` de `FMOD_CREATESOUNDEXINFO` = `mov w8,#0xe0` (224) em `CreateRAWPlaySound`/`CreateRecordSound`;
`FMOD_DSP_DESCRIPTION` montada para o DSP "Acoustic echo canceller far capturer" (nome em +4, `numoutputbuffers` em +0x2c, callback `read` em +0x48).
`FMOD_STUDIO_ADVANCEDSETTINGS` nao e usada pela dylib. Todos batem com o layout dos cabecalhos Windows.

### 2.4 Callbacks FMOD -> dylib
Windows ARM64 e Apple coincidem nos registradores; nenhum callback implementado pela dylib passa de 8 argumentos:
`EventInstanceCallback::call(type, event, params)` (3), leitura do DSP de captura (6), callbacks de log/memoria (<= 5). Nao foi preciso um shim
tipo `macho_jni_bridge` para callbacks. (Os unicos tipos de callback com mais de 8 parametros nos cabecalhos sao do plugin de saida e das funcoes
de pan do DSP, que a dylib nao usa.) A dylib chama o Java a partir da thread nativa do Studio com `JNIEnvTLD` (GetEnv, senao AttachCurrentThread, uma vez por
thread); com o wrapper de `JavaVM`/`JNIEnv`, `started(J)`, `stopped(J)` e `timelineMarker(J,String,int)` chegam ao Java.

## 3. FmodHarness (`tests/harness/FmodHarness.java`)
Ordem real de `FMODManager.init()`: `javafmodJNI.init()` (loadLibrary `fmod`, `fmodstudio`, `fmodintegration64`) -> `FMOD_System_Create` ->
`FMOD_System_Init(1024, 0, 131840)` -> `Set3DSettings(1,1,1)` -> bancos na ordem do jogo (`ZomboidSound.strings`, `ZomboidMusic`, `ZomboidSound`,
`ZomboidSoundMP` existem; `Animal`...`Zombie` nao existem em `media/sound/banks/Desktop` e falham igual nos dois lados) -> `CreateChannelGroup("InGameNonBank")`
-> listagem (4 bancos, 3494 eventos, 17 VCAs, 43 parametros globais) -> bus (volume/mute/paused: shims de `bool`) -> evento `event:/Broadcast/Emergency`
(6550 ms): sample data, instancia, `SetCallback` (STARTED|STOPPED), 3D, pausa, `StartEvent`, estado de reproducao, 2 s tocando, posicao na timeline,
`Stop`, estado final (STOPPED), `ReleaseEventInstance`, 3 ciclos extras (callbacks de uma unica thread nativa, anexada uma vez) e o marcador de timeline de
`event:/Music/Main` (`ReStart@0`, com `String` e `int`), como a musica do jogo.

Comandos: `.\tests\harness\run-native.ps1 -Lib fmod -Arch x64` e `-Arch arm64`; `python -I tests/harness/compare.py reports/native-fmod-x64.txt reports/native-fmod-arm64.txt`.
Resultado (3 execucoes ARM64 seguidas contra o x64): `OK: 82 linhas comparadas, 0 divergencias`; `failures=0`. `callback_distinct_threads=1`
(Review Focus 3: a thread nativa e anexada uma unica vez; nome `Thread-0`). Sem `hs_err`.
Arquivos: `reports/native-fmod-x64.txt`, `reports/native-fmod-arm64.txt`, tempos em `reports/native-fmod-time-*.txt`.
O ARM64 usa `build/fmod-dist/` (`fmodintegration64.dll` e `pzdarwin.dll` de `dist/` + as DLLs FMOD ARM64 de `third-party/fmod/`); `dist/fmod.dll` e
`dist/fmodstudio.dll` continuam vazias ate a Tarefa 4.

### Desvio do plano: NOSOUND_NRT
`FMOD_System_SetOutput(NOSOUND_NRT)` nao e alcancavel pelo Java das JNI do jogo: exige o ponteiro do sistema core ANTES de `FMOD_System_Init`, e nenhuma JNI o devolve
(`FMOD_System_Create` devolve `int` e guarda o ponteiro num global da dylib; os ponteiros do FMOD sao enderecos de heap, nao adivinhaveis). O modo padrao do
harness abre o dispositivo normalmente e silencia o grupo master (`FMOD_ChannelGroup_SetMute` + volume 0) logo apos `Init`, identico em x64 e ARM64.
O modo `-Audible` nao silencia e toca mais tempo (evento 5 s, musica +4 s).

## 4. Stub e launcher
- `fmodintegration64` saiu da lista de stubs (`pz_stub_dll`) e virou `pz_bridge_dll(fmodintegration64 libfmodintegration.dylib FMOD)`; `test_stub_counts` agora cobre
  `ZNetNoSteam64`, `ZNetJNI64`, `bink64`, `imgui-java64`, e `test_bridged_not_stub` confirma os 437 `Java_*` iguais aos da DLL do jogo, ARM64 e `JNI_OnLoad`.
- Launcher inalterado: o manifesto ja lista `fmodintegration64.dll`, e o jogo continua com `-nosound` (a ponte so carrega com som; ate a Tarefa 4 as
  `dist/fmod.dll`/`fmodstudio.dll` vazias fariam `JNI_OnLoad` falhar listando os simbolos ausentes).

## 5. Limites conhecidos
- Sem teste automatizado de audio real (nao feito, por ordem). Reproducao audivel: `.\tests\harness\run-native.ps1 -Lib fmod -Arch arm64 -Audible`.
- A lista de 61 shims e fechada: se a dylib mudar (outra versao do jogo), o hash `BRIDGE_DYLIB_SHA256` recusa a carga e o gerador precisa ser rodado de novo.
