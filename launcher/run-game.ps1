<#
Inicia o Project Zomboid 42.21 com a JVM ARM64 e as DLLs ARM64 de dist\.
Uso: .\run-game.ps1 [-Timeout <s>] [-DryRun] [-GameDir <p>] [-DistDir <p>] [-CacheDir <p>] [-JavaAgent <jar>] [-DebugGl] [-NoAgent] [-NoFboChunk] [-NoCollapseFix] [-VboSync none|sync|invalidate] [-UiDiag] [-UiModelDiag] [-GlSync none|<lista>] [-Resolution <L>x<A>] [-FrameRate <N>] [-DpiUnaware] [-StutterDiag] [-GcLog] [-UseFboChunk] [-NoSound] [-UserOptions <ini>]
  -Timeout 0 = sem limite. -DryRun valida tudo, prepara cache\options.ini e imprime a linha de comando (nao abre o jogo).
Codigos de saida: 2 = erro de ambiente, 3 = projectzomboid.jar diferente do manifesto, 4 = dist invalido.
O agente dist\pzarm-agent.jar roda sempre em modo fps (grava logs\fps-<arch>-<timestamp>.csv); -NoAgent desliga. Build: launcher\agent\build.ps1.
-NoSound = adiciona -nosound (som desligado). Padrao: som LIGADO (fmod.dll/fmodstudio.dll ARM64 reais em dist\; a ponte fmodintegration64.dll).
-DebugGl = soma o diagnostico de FBO: MESA_DEBUG=incomplete_fbo,context, -Dzomboid.opengl.debugcontext=1 e modo diag do agente (grava logs\fbo-debug.log).
Nada e escrito na pasta do jogo. PATH so muda neste processo.
  -Env CHAVE=VALOR (varias: -Env A=1,B=2) = variavel de ambiente so no processo do jogo.
  -PzSet PerformanceSettings.fboRenderChunk=false (ou useFbos; varias com virgula) = campo estatico definido pelo agente (precisa do agente).
  PADRAO (com agente): fboRenderChunk=true (IsoCell.renderInternal desenha os chunks em FBO), glsync=swapfence,uploadflush,otherflush + vbosync=sync (fence glFenceSync/glClientWaitSync atrasado de 1 quadro antes da troca de buffers, em vez do glFinish: contorno do hazard de sincronizacao CPU-GPU do Mesa GLon12/D3D12, que fazia sprites com textura errada e o boneco do menu sumir; mantem CPU e GPU em paralelo; validado em jogo numa sessao curta numa maquina, sem comparacao controlada; e um contorno, nao correcao do Mesa; -GlSync swapfinish volta ao glFinish antigo, que travava a CPU e custou FPS mediana 60 -> 37), sem renderfix e collapse=player (a casa nao abre de perto). Ver docs/RESULTS.md.
  -NoFboChunk = volta ao caminho antigo: fboRenderChunk=false, sem renderfix e sem collapse. Na percepcao costuma parecer mais rapido que o modo FBO. Com -RenderFix ou -NoCollapseFix e contraditorio (exit 2).
  -UseFboChunk = aceito e SEM EFEITO (o modo FBO ja e o padrao); existe so para nao quebrar comandos antigos. Com -NoFboChunk e contraditorio (exit 2).
  -NoCollapseFix = desliga so o collapse=player (a casa volta a abrir de perto).
  -RenderFix (inclusive none) ajusta o contorno de textura do modo FBO.
  Um -PzSet explicito de fboRenderChunk tem prioridade: =false desfaz o modo FBO (sem renderfix padrao nem collapse, e -RenderFix flushchunk/flushtexchunk falha com exit 2); =true e o padrao; fica registrado no cabecalho do log. Com -NoAgent nada disso se aplica (so registra "nao aplicavel").
  -RenderDiag [-RenderDiagEvery N] = diagnostico de textura (docs/RESULTS.md): antes de cada glDrawRangeElements com tileWithDepth/opaqueWithDepth le programa, uniforms DIFFUSE/DEPTH e GL_TEXTURE_BINDING_2D das unidades 0/1 (sem alterar estado) e grava logs\render-diag.log. N = amostra 1 a cada N desenhos (padrao 64; 1 = todos, lento). Precisa do agente.
  -RenderFix none|rebind|flush|uniforms|dirty|dirtyprog|flushtex|flushchunk|flushtexchunk = contorno do bug de textura (um por vez; padrao: nenhum, o glsync padrao o tornou redundante; none = nenhum, explicito; flushtex volta o contorno antigo): rebind = religa DIFFUSE/DEPTH antes de cada desenho; flush = glFlush apos cada desenho; uniforms = reenvia glUniform1i DIFFUSE=0/DEPTH=1; dirty = bind 0 e depois bind real nas unidades 0/1 (sem glFlush); dirtyprog = dirty + glUseProgram(0)/glUseProgram(atual). flushtex = glFlush antes do desenho tile/opaque so quando o id de texture0 ou texture1 mudou em relacao ao desenho tile anterior; flushchunk = como flush, mas so enquanto um chunk e renderizado para o proprio FBO; flushtexchunk = regra do flushtex so dentro do chunk.
  -RenderFlushEvery N (padrao 1; so com -RenderFix flush) = glFlush a cada N desenhos tile/opaque. O agente loga flush/s em logs\render-diag.log.
  -VboSync none|sync|invalidate = contorno para o glMapBufferRange UNSYNCHRONIZED (validado em jogo so numa sessao curta, numa maquina, em combinacao com o glsync padrao): GLVertexBufferObject mapeia os buffers com acesso 0x26 (WRITE|INVALIDATE_RANGE|UNSYNCHRONIZED) e no Mesa D3D12 a CPU pode reescrever vertices que a GPU ainda nao consumiu (sprites com textura/UV errados, piscando). Padrao: sync (junto com o glsync=swapfence,uploadflush,otherflush padrao; validado em jogo numa sessao curta). sync = o agente remove UNSYNCHRONIZED (0x26 -> 0x6); invalidate = como sync e, com offset 0, INVALIDATE_RANGE vira INVALIDATE_BUFFER (0xA); none = nao passa vbosync ao agente. Precisa do agente (com -NoAgent e exit 2).
  -UiDiag = diagnostico (sem alterar estado): descobre se o preview do personagem no menu some porque o jogo pula o desenho em alguns quadros. Adiciona uidiag ao agente, que conta init/render/postRender de UI3DModel$Drawer (e o retorno de renderMain) e grava logs\uidiag.log uma vez por segundo. Precisa do agente (com -NoAgent e exit 2).
  -UiModelDiag = diagnostico: adiciona uimodeldiag ao agente. Em cada quadro do preview 3D (UI3DModel$Drawer.render) le por glReadPixels uma grade de pixels do retangulo do boneco antes e depois do DoRender, conta os que mudaram e registra o estado GL (framebuffer, viewport, scissor, depth, blend...) e a posicao do mouse em logs\uimodel.log. Custa dois glReadPixels por quadro. Precisa do agente (com -NoAgent e exit 2).
  -GlSync swapfinish,swapflush,swapfence,modelfinish,modelflush,uploadfinish,uploadflush,uploadfinishall,uploadflushall,uploadcount,otherflush,otherfinish,clearfinish,clearflush (lista separada por virgula; qualquer subconjunto, maiusculas aceitas; padrao com agente: swapfence,uploadflush,otherflush, nos modos FBO e -NoFboChunk; none desliga) = contorno de sincronizacao CPU-GPU do Mesa GLon12/D3D12 (o x64 emulado tambem sofre; o uimodeldiag mostrou que o jogo desenha todo quadro e que forcar sincronizacao resolve): swapfinish/swapflush = glFinish/glFlush antes de cada glfwSwapBuffers; swapfence = fence glFenceSync/glClientWaitSync atrasado de 1 quadro antes de cada troca (sem drenar a GPU; padrao); modelfinish/modelflush = glFinish/glFlush logo depois do desenho do boneco do menu (UI3DModel$Drawer.render). uploadfinish/uploadflush = glFinish/glFlush antes de cada upload/copia de textura ou buffer (glTexSubImage2D, glBufferData, glMapBufferRange, glBlitFramebuffer...) SO se a thread de render fez desenho/clear/blit/copia desde o ultimo flush, finish ou swap (sem redundancia); uploadflushall/uploadfinishall = o comportamento antigo (a cada upload); otherflush/otherfinish = glFlush/glFinish DEPOIS de cada upload feito por threads que nao sao a de render e tem contexto GL atual; clearfinish/clearflush = o mesmo antes de glClear; uploadcount = so conta (diagnostico; o agente imprime os pontos de insercao por nome em stderr). O agente grava logs\glsync.log (1 linha/s com as contagens). Precisa do agente (com -NoAgent e exit 2).
  -Resolution <L>x<A> (ex.: 1920x1280; validado por ^\d{3,4}x\d{3,4}$) = grava width= e height= em cache\options.ini (SO a copia do cache; nunca a pasta do jogo nem %USERPROFILE%) antes de iniciar e define __COMPAT_LAYER=DPIUNAWARE no processo do jogo (a menos que -Env ja defina __COMPAT_LAYER: o -Env vale). Serve para desenhar em menos pixels e deixar o Windows esticar a janela (tela de alta densidade com escala > 100%).
  -FrameRate <N> (10 a 240) = grava frameRate=N em cache\options.ini (SO a copia do cache, como o -Resolution; limita o FPS do jogo). Cabecalho: 'framerate: ...'. Sem a opcao, o valor ja gravado antes em cache\options.ini continua valendo (o cabecalho mostra o valor efetivo e a origem).
  -StutterDiag = diagnostico de travadas: adiciona stutterdiag ao agente, que grava logs\stutter.log com os quadros >= 45 ms (tempo total, tempo em glFinish/glFlush, uploads, chunks FBO, GC, espera pela thread principal) e um resumo a cada 10 s. Implica contar os uploads (preUpload). Precisa do agente (com -NoAgent e exit 2).
  -GcLog = acrescenta -Xlog:gc*,safepoint:file=logs\gc-<timestamp>.log:uptime,time,level,tags a JVM (coletas e safepoints; nao precisa do agente).
  -DpiUnaware = so o __COMPAT_LAYER=DPIUNAWARE (respeitando -Env), sem mexer na resolucao. Sem -Resolution nem -DpiUnaware o dpi nao muda; os width/height/frameRate ja gravados em cache\options.ini continuam valendo (o cabecalho mostra o valor efetivo e a origem: opcao, cache de execucao anterior ou copia do options.ini do usuario). Cabecalho: 'resolucao: ...' e 'dpi: ...'. Nao precisam do agente.
  Os valores efetivos (fboRenderChunk, renderfix, collapse, vbosync e glsync, com a origem padrao/opcao) e os demais ativos vao para o cabecalho de logs\game-*.log e o agente imprime "[pzarm-agent] pz-set aplicado: ..." no stderr (tambem no log).
#>
param(
    [int]$Timeout = 0,
    [switch]$DryRun,
    [Alias('Env')][string[]]$EnvVars = @(),
    [string[]]$PzSet = @(),
    [string]$GameDir = $(if ($env:PZ_GAME_DIR) { $env:PZ_GAME_DIR } else { 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' }),
    [string]$DistDir = '',
    [string]$CacheDir = '',
    [string]$JavaAgent = '',
    [switch]$DebugGl,
    [switch]$NoAgent,
    [switch]$NoFboChunk,
    [switch]$UseFboChunk,
    [switch]$NoCollapseFix,
    [ValidateSet('none', 'sync', 'invalidate')][string]$VboSync = '',
    [switch]$RenderDiag,
    [switch]$UiDiag,
    [switch]$UiModelDiag,
    [string[]]$GlSync = @(),
    [string]$Resolution = '',
    [switch]$DpiUnaware,
    [switch]$StutterDiag,
    [switch]$GcLog,
    [int]$FrameRate = 0,
    [ValidateRange(1, 1000000)][int]$RenderDiagEvery = 64,
    [ValidateSet('none', 'rebind', 'flush', 'uniforms', 'dirty', 'dirtyprog', 'flushtex', 'flushchunk', 'flushtexchunk')][string]$RenderFix = '',
    [int]$RenderFlushEvery = 1,
    [switch]$NoSound,
    [string]$UserOptions = (Join-Path $env:USERPROFILE 'Zomboid\options.ini')
)
if ($VboSync) { $VboSync = $VboSync.ToLowerInvariant() }   # ValidateSet ignora maiusculas/minusculas mas nao normaliza
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $DistDir)  { $DistDir  = Join-Path $Root 'dist' }
if (-not $CacheDir) { $CacheDir = Join-Path $Root 'cache' }
$LogDir = Join-Path $Root 'logs'

function Fail([int]$code, [string]$msg) { [Console]::Error.WriteLine("ERRO: $msg"); exit $code }
function Full([string]$p) { [IO.Path]::GetFullPath($p).TrimEnd('\') }

# --- -Env / -PzSet (diagnostico de renderizacao) ---
$EnvPairs = [ordered]@{}
foreach ($e in @($EnvVars | ForEach-Object { $_ -split "," } | Where-Object { $_ })) {
    if ($e -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { Fail 2 "-Env invalido (use CHAVE=VALOR): $e" }
    $EnvPairs[$Matches[1]] = $Matches[2]
}
if ($Resolution -and $Resolution -cnotmatch '^\d{3,4}x\d{3,4}$') { Fail 2 "-Resolution invalido (use LARGURAxALTURA, ex.: 1920x1280): $Resolution" }
if ($PSBoundParameters.ContainsKey('FrameRate') -and ($FrameRate -lt 10 -or $FrameRate -gt 240)) { Fail 2 "-FrameRate invalido (inteiro de 10 a 240): $FrameRate" }
$FrameEff = if ($PSBoundParameters.ContainsKey('FrameRate')) { "$FrameRate (origem: opcao -FrameRate; gravado so em cache\options.ini)" } else { 'nao alterado (padrao)' }
$ResEff = 'nao alterada (padrao)'
$DpiEff = 'nenhum (padrao)'
if ($Resolution) { $ResEff = "$Resolution (origem: opcao -Resolution; gravada so em cache\options.ini)" }
if ($Resolution -or $DpiUnaware) {
    $origin = if ($Resolution) { '-Resolution' } else { '-DpiUnaware' }
    if ($EnvPairs.Contains('__COMPAT_LAYER')) { $DpiEff = "$($EnvPairs['__COMPAT_LAYER']) (origem: -Env explicito; $origin respeitou)" }
    else { $EnvPairs['__COMPAT_LAYER'] = 'DPIUNAWARE'; $DpiEff = "DPIUNAWARE (origem: opcao $origin)" }
}
$PzSets = @()
foreach ($z in @($PzSet | ForEach-Object { $_ -split "," } | Where-Object { $_ })) {
    if ($z -notmatch '^PerformanceSettings\.(fboRenderChunk|useFbos)=(true|false)$') { Fail 2 "-PzSet invalido (permitido: PerformanceSettings.fboRenderChunk|useFbos=true|false): $z" }
    $PzSets += $z
}
# padrao: fboRenderChunk=true + glsync=swapfence,uploadflush,otherflush + vbosync=sync + collapse=player (sem renderfix); -NoFboChunk volta ao caminho antigo (fboRenderChunk=false, sem renderfix e sem collapse)
# -UseFboChunk e aceito sem efeito (o padrao ja e o modo FBO); so contradiz -NoFboChunk
if ($NoFboChunk -and $UseFboChunk) { Fail 2 "-NoFboChunk e -UseFboChunk sao contraditorios" }
if ($RenderFix -and $NoFboChunk) { Fail 2 "-RenderFix $RenderFix nao vale com -NoFboChunk (sem o modo FBO nao ha renderfix padrao)" }
if ($NoCollapseFix -and $NoFboChunk) { Fail 2 "-NoCollapseFix nao vale com -NoFboChunk (sem o modo FBO nao ha collapse=player)" }
$PzFbo = @($PzSets | Where-Object { $_ -like 'PerformanceSettings.fboRenderChunk=*' })
$PzFboValue = $null
if ($PzFbo.Count) { $PzFboValue = ($PzFbo[-1] -split '=')[1] -eq 'true' }
$FboEff = 'nao aplicavel (-NoAgent: sem agente; o jogo usa o proprio padrao)'
$FixEff = 'nao aplicavel (-NoAgent)'
$ColEff = 'nao aplicavel (-NoAgent)'
$EffRenderFix = ''
$EffCollapse = $false
$VboEff = 'nao aplicavel (-NoAgent)'
$EffVbo = ''
if (-not $NoAgent) {
    if ($VboSync -eq 'none') { $VboEff = 'nenhum (origem: opcao -VboSync none)' }
    elseif ($VboSync) { $EffVbo = $VboSync; $VboEff = "$VboSync (origem: opcao -VboSync)" }
    else { $EffVbo = 'sync'; $VboEff = 'sync (origem: padrao)' }
    if ($null -ne $PzFboValue) {
        $fboOn = $PzFboValue
        $FboEff = "$("$fboOn".ToLower()) (origem: -PzSet explicito)"
    } elseif ($NoFboChunk) {
        $fboOn = $false; $PzSets += 'PerformanceSettings.fboRenderChunk=false'
        $FboEff = 'false (origem: opcao -NoFboChunk)'
    } else {
        $fboOn = $true; $PzSets += 'PerformanceSettings.fboRenderChunk=true'
        $FboEff = 'true (origem: padrao)'
    }
    if ($fboOn) {
        if ($RenderFix -eq 'none') { $FixEff = 'nenhum (origem: opcao -RenderFix none)' }
        elseif ($RenderFix) { $EffRenderFix = $RenderFix; $FixEff = "$RenderFix (origem: opcao -RenderFix)" }
        else { $FixEff = 'nenhum (origem: padrao do modo FBO)' }
        if ($NoCollapseFix) { $ColEff = 'nenhum (origem: opcao -NoCollapseFix)' }
        else { $EffCollapse = $true; $ColEff = 'player (origem: padrao do modo FBO)' }
    } else {
        # fboRenderChunk=false (-NoFboChunk ou -PzSet explicito): -PzSet explicito pode combinar com -RenderFix
        if ($RenderFix -in 'flushchunk', 'flushtexchunk') { Fail 2 "-RenderFix $RenderFix so atua dentro do chunk FBO; com fboRenderChunk=false ($FboEff) nao faria nada" }
        if ($RenderFix -eq 'none') { $FixEff = 'nenhum (origem: opcao -RenderFix none)' }
        elseif ($RenderFix) { $EffRenderFix = $RenderFix; $FixEff = "$RenderFix (origem: opcao -RenderFix)" }
        else { $FixEff = 'nenhum (fboRenderChunk=false: sem renderfix padrao)' }
        $ColEff = 'nenhum (fboRenderChunk=false: collapse=player so vale no modo FBO)'
    }
}
if ($RenderFlushEvery -lt 1 -or $RenderFlushEvery -gt 1000000) { Fail 2 "-RenderFlushEvery invalido (inteiro de 1 a 1000000): $RenderFlushEvery" }
if ($PSBoundParameters.ContainsKey('RenderFlushEvery') -and $RenderFix -ne 'flush') { Fail 2 "-RenderFlushEvery so vale com -RenderFix flush" }
$GlSyncs = @()
$GlNone = $false
foreach ($g in @($GlSync | ForEach-Object { "$_" -split "," } | Where-Object { $_ })) {
    $g = $g.Trim().ToLowerInvariant()
    if ($g -eq 'none') { $GlNone = $true; continue }
    if ($g -notin 'swapfinish', 'swapflush', 'swapfence', 'modelfinish', 'modelflush', 'uploadfinish', 'uploadflush', 'uploadfinishall', 'uploadflushall', 'uploadcount', 'otherflush', 'otherfinish', 'clearfinish', 'clearflush') { Fail 2 "-GlSync invalido (none|swapfinish|swapflush|swapfence|modelfinish|modelflush|uploadfinish|uploadflush|uploadfinishall|uploadflushall|uploadcount|otherflush|otherfinish|clearfinish|clearflush): $g" }
    if ($GlSyncs -notcontains $g) { $GlSyncs += $g }
}
if ($GlNone -and $GlSyncs.Count) { Fail 2 "-GlSync none nao combina com outros valores" }
if (($GlNone -or $GlSyncs.Count) -and $NoAgent) { Fail 2 "-GlSync precisa do agente; nao combine com -NoAgent" }
if (-not $NoAgent -and -not $GlNone -and -not $GlSyncs.Count) { $GlSyncs = @('swapfence', 'uploadflush', 'otherflush'); $GlDefault = $true } else { $GlDefault = $false }
if ($StutterDiag -and $NoAgent) { Fail 2 "-StutterDiag precisa do agente; nao combine com -NoAgent" }
if ($UiModelDiag -and $NoAgent) { Fail 2 "-UiModelDiag precisa do agente; nao combine com -NoAgent" }
if ($UiDiag -and $NoAgent) { Fail 2 "-UiDiag precisa do agente; nao combine com -NoAgent" }
if ($VboSync -and $NoAgent) { Fail 2 "-VboSync $VboSync precisa do agente; nao combine com -NoAgent" }
if ($NoCollapseFix -and $NoAgent) { Fail 2 "-NoCollapseFix precisa do agente; nao combine com -NoAgent" }
if (($RenderDiag -or $RenderFix) -and $NoAgent) { Fail 2 "-RenderDiag/-RenderFix precisam do agente; nao combine com -NoAgent" }
$GlEff = if ($NoAgent) { 'nao aplicavel (-NoAgent)' } elseif ($GlNone) { 'nenhum (origem: opcao -GlSync none)' } elseif ($GlDefault) { 'swapfence,uploadflush,otherflush (origem: padrao)' } else { ($GlSyncs -join ',') + ' (origem: opcao -GlSync)' }
$envDesc = if ($EnvPairs.Count) { ($EnvPairs.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ' } else { '(nenhuma)' }
$pzDesc  = if ($PzSets.Count) { $PzSets -join ' ' } else { '(nenhum)' }
$Hdr = "fboRenderChunk efetivo: $FboEff`nrenderfix efetivo: $FixEff`ncollapse efetivo: $ColEff`nvbosync efetivo: $VboEff`nglsync efetivo: $GlEff`nresolucao: $ResEff`ndpi: $DpiEff`nframerate: $FrameEff`nEnv (so no processo do jogo): $envDesc`nPzSet (agente Java, aplicado ao fim do <clinit> de PerformanceSettings): $pzDesc`n"

# Nomes exigidos em dist\ (o manifesto precisa listar todos)
$Required = @(
    'lwjgl.dll','glfw.dll','lwjgl_opengl.dll','lwjgl_stb.dll','lwjgl_tinyfd.dll','jemalloc.dll',
    'Lighting64.dll','PZPopMan64.dll','PZPathFind64.dll','PZBullet64.dll','PZClipper64.dll','jassimp64.dll','pzdarwin.dll',
    'ZNetNoSteam64.dll','ZNetJNI64.dll','fmodintegration64.dll','bink64.dll','imgui-java64.dll',
    'RakNet64.dll','steam_api64.dll','fmod.dll','fmodstudio.dll','bink2w64.dll'
)

function Get-Machine([string]$path) {
    $fs = [IO.File]::OpenRead($path)
    try {
        $h = New-Object byte[] 64
        if ($fs.Read($h, 0, 64) -lt 64 -or $h[0] -ne 0x4D -or $h[1] -ne 0x5A) { return 'nao-PE' }
        $off = [BitConverter]::ToInt32($h, 0x3C)
        $null = $fs.Seek($off, 'Begin')
        $s = New-Object byte[] 6
        if ($fs.Read($s, 0, 6) -lt 6 -or $s[0] -ne 0x50 -or $s[1] -ne 0x45 -or $s[2] -ne 0 -or $s[3] -ne 0) { return 'nao-PE' }
        $m = [BitConverter]::ToUInt16($s, 4)
        switch ($m) { 0xAA64 { 'arm64' } 0x8664 { 'x64' } 0x14C { 'x86' } default { '0x{0:X4}' -f $m } }
    } finally { $fs.Dispose() }
}

# --- ambiente ---
$GameDir = Full $GameDir; $DistDir = Full $DistDir; $CacheDir = Full $CacheDir
$Java = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin\java.exe'
$Jar  = Join-Path $GameDir 'projectzomboid.jar'
$JsonPath = Join-Path $GameDir 'ProjectZomboid64.json'
if (-not (Test-Path -LiteralPath $Java))     { Fail 2 "java.exe ARM64 nao encontrado: $Java" }
if (-not (Test-Path -LiteralPath $GameDir))  { Fail 2 "pasta do jogo nao encontrada: $GameDir" }
if (-not (Test-Path -LiteralPath $JsonPath)) { Fail 2 "ProjectZomboid64.json nao encontrado em: $GameDir" }
if (-not (Test-Path -LiteralPath $Jar))      { Fail 2 "projectzomboid.jar nao encontrado: $Jar" }
if (($DistDir + '\').StartsWith($GameDir + '\', [StringComparison]::OrdinalIgnoreCase) -or
    ($GameDir + '\').StartsWith($DistDir + '\', [StringComparison]::OrdinalIgnoreCase)) {
    Fail 2 "dist ($DistDir) e a pasta do jogo ($GameDir) nao podem se sobrepor: java.library.path nunca aponta para o jogo"
}
if (($CacheDir + '\').StartsWith($GameDir + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail 2 "cache nao pode ficar dentro da pasta do jogo" }
if ($JavaAgent -and -not (Test-Path -LiteralPath $JavaAgent)) { Fail 2 "agente Java nao encontrado: $JavaAgent" }
$AgentJar = Join-Path $Root 'dist\pzarm-agent.jar'
if ($NoAgent -and $PzSets.Count) { Fail 2 "-PzSet precisa do agente; nao combine com -NoAgent" }
if ($NoAgent -and $DebugGl) { Fail 2 "-DebugGl precisa do agente; nao combine com -NoAgent" }
if (-not $NoAgent -and -not (Test-Path -LiteralPath $AgentJar)) { Fail 2 "agente ausente: $AgentJar (rode launcher\agent\build.ps1 ou use -NoAgent)" }
try { $cfg = Get-Content -LiteralPath $JsonPath -Raw | ConvertFrom-Json }
catch { Fail 2 "ProjectZomboid64.json invalido: $($_.Exception.Message)" }
foreach ($f in 'mainClass','classpath','vmArgs') { if ($null -eq $cfg.$f) { Fail 2 "campo '$f' ausente em $JsonPath" } }

# --- dist ---
if (-not (Test-Path -LiteralPath $DistDir)) { Fail 4 "dist nao encontrado: $DistDir" }
$ManPath = Join-Path $DistDir 'manifest.json'
if (-not (Test-Path -LiteralPath $ManPath)) { Fail 4 "manifest.json ausente em $DistDir (rode tools\make_manifest.py)" }
try { $man = Get-Content -LiteralPath $ManPath -Raw | ConvertFrom-Json }
catch { Fail 4 "manifest.json invalido: $($_.Exception.Message)" }
foreach ($n in $Required) {
    $p = Join-Path $DistDir $n
    if (-not (Test-Path -LiteralPath $p)) {
        if ($n -in 'fmod.dll','fmodstudio.dll') { Fail 4 "DLL ausente em dist: $n (coloque fmod.dll e fmodstudio.dll ARM64 do seu FMOD Studio API em third-party/fmod e rode build.ps1; ver README)" }
        Fail 4 "DLL ausente em dist: $n"
    }
    $arch = Get-Machine $p
    if ($arch -ne 'arm64') { Fail 4 "DLL nao e arm64 ($arch): $n" }
    $e = if ($man.dlls) { $man.dlls.$n } else { $null }
    if (-not $e) { Fail 4 "DLL fora do manifesto: $n" }
    if ((Get-Item -LiteralPath $p).Length -ne $e.size -or (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() -ne "$($e.sha256)".ToLower()) {
        Fail 4 "DLL difere do manifesto: $n"
    }
}
foreach ($f in Get-ChildItem -LiteralPath $DistDir -Filter *.dll) {
    if ($Required -notcontains $f.Name) {
        $arch = Get-Machine $f.FullName
        if ($arch -ne 'arm64') { Fail 4 "DLL nao e arm64 ($arch): $($f.Name)" }
    }
}
# --- jar ---
$jarHash = (Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash.ToLower()
if ($jarHash -ne "$($man.jar_sha256)".ToLower() -or (Get-Item -LiteralPath $Jar).Length -ne $man.jar_size) {
    Fail 3 "projectzomboid.jar diverge do manifesto (sha256 $jarHash; esperado $($man.jar_sha256)). Refaca o dist para esta versao do jogo."
}

# --- cache\options.ini ---
New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
$Opt = Join-Path $CacheDir 'options.ini'
$utf8 = New-Object Text.UTF8Encoding($false)
$OptOrigin = ''
if (Test-Path -LiteralPath $Opt) { $txt = [IO.File]::ReadAllText($Opt); $OptOrigin = 'cache\options.ini de execucao anterior' }
elseif (Test-Path -LiteralPath $UserOptions) { $txt = [IO.File]::ReadAllText($UserOptions); $OptOrigin = 'copia do options.ini do usuario no cache' }   # so leitura
else { $txt = '' }
$nl = if ($txt -match "`r`n" -or $txt -eq '') { "`r`n" } elseif ($txt -match "`n") { "`n" } else { "`r`n" }
if ($txt -match '(?im)^doContainerOutline=') {
    $txt = [regex]::Replace($txt, '(?im)^doContainerOutline=[^\r\n]*', 'doContainerOutline=false')
} else {
    if ($txt -ne '' -and -not $txt.EndsWith("`n")) { $txt += $nl }
    $txt += "doContainerOutline=false$nl"
}
if ($PSBoundParameters.ContainsKey('FrameRate')) {
    # so a copia do cache (nunca a pasta do jogo nem %USERPROFILE%)
    if ($txt -match '(?im)^frameRate=') { $txt = [regex]::Replace($txt, '(?im)^frameRate=[^\r\n]*', "frameRate=$FrameRate") }
    else {
        if ($txt -ne '' -and -not $txt.EndsWith("`n")) { $txt += $nl }
        $txt += "frameRate=$FrameRate$nl"
    }
}
if ($Resolution) {
    # so a copia do cache (nunca a pasta do jogo nem %USERPROFILE%)
    $rw, $rh = $Resolution -split 'x'
    foreach ($kv in @(@('width', $rw), @('height', $rh))) {
        if ($txt -match "(?im)^$($kv[0])=") { $txt = [regex]::Replace($txt, "(?im)^$($kv[0])=[^\r\n]*", "$($kv[0])=$($kv[1])") }
        else {
            if ($txt -ne '' -and -not $txt.EndsWith("`n")) { $txt += $nl }
            $txt += "$($kv[0])=$($kv[1])$nl"
        }
    }
}
[IO.File]::WriteAllText($Opt, $txt, $utf8)
# cabecalho: sem a opcao, mostra o valor efetivo que ficou no cache\options.ini
if ($OptOrigin) {
    if (-not $Resolution -and $txt -match '(?im)^width=(\d+)' ) {
        $ew = $Matches[1]
        if ($txt -match '(?im)^height=(\d+)') { $Hdr = $Hdr.Replace("resolucao: $ResEff`n", "resolucao: ${ew}x$($Matches[1]) (origem: $OptOrigin)`n") }
    }
    if (-not $PSBoundParameters.ContainsKey('FrameRate') -and $txt -match '(?im)^frameRate=(\d+)') { $Hdr = $Hdr.Replace("framerate: $FrameEff`n", "framerate: $($Matches[1]) (origem: $OptOrigin)`n") }
}

# --- linha de comando ---
$winArgs = @()
if ($cfg.windows) {
    $osVer = [Environment]::OSVersion.Version
    $best = $null; $bestVer = $null
    foreach ($prop in $cfg.windows.PSObject.Properties) {
        $v = $null
        if ([version]::TryParse($prop.Name, [ref]$v) -and $v -le $osVer -and ($null -eq $bestVer -or $v -gt $bestVer)) { $best = $prop.Value; $bestVer = $v }
    }
    if ($best -and $best.vmArgs) { $winArgs = @($best.vmArgs) }
}
$winArgs = @($winArgs | Where-Object { @($cfg.vmArgs) -notcontains $_ })
$vm = @((@($cfg.vmArgs) + $winArgs) | Where-Object { $_ -notmatch '^-Djava\.library\.path=' -and $_ -notmatch '^-Dorg\.lwjgl\.librarypath=' -and $_ -notmatch '^-Xms' })
$agent = @(); if ($JavaAgent) { $agent = @("-javaagent:$((Full $JavaAgent))") }
if (-not $NoAgent) {
    $mode = "fps,dir=$LogDir"
    if ($DebugGl) { $mode += ",diag,log=$LogDir\fbo-debug.log"; $agent += '-Dzomboid.opengl.debugcontext=1' }
    foreach ($z in $PzSets) { $mode += ",pz=$z" }
    if ($RenderDiag) { $mode += ",renderdiag,diagevery=$RenderDiagEvery" }
    if ($EffRenderFix) { $mode += ",renderfix=$EffRenderFix" }
    if ($EffRenderFix -eq 'flush') { $mode += ",flushevery=$RenderFlushEvery" }
    if ($EffCollapse) { $mode += ",collapse=player" }
    if ($EffVbo) { $mode += ",vbosync=$EffVbo" }
    if ($UiDiag) { $mode += ",uidiag" }
    if ($UiModelDiag) { $mode += ",uimodeldiag" }
    foreach ($g in $GlSyncs) { $mode += ",glsync=$g" }
    if ($StutterDiag) { $mode += ",stutterdiag" }
    $agent += "-javaagent:$AgentJar=$mode"
}
$cp = (@($cfg.classpath) -join ';')
$gcLogArg = @(); if ($GcLog) { $gcLogArg = @("-Xlog:gc*,safepoint:file=$LogDir\gc-$(Get-Date -Format 'yyyyMMdd-HHmmss').log:uptime,time,level,tags") }
$argList = $vm + @("-Djava.library.path=$DistDir", "-Dorg.lwjgl.librarypath=$DistDir", "-XX:ErrorFile=$LogDir\hs_err_%p.log") + $gcLogArg + $agent +
    @('-cp', $cp, $cfg.mainClass, '-nosteam') + $(if ($NoSound) { @('-nosound') } else { @() }) + @("-cachedir=$CacheDir")
$quoted = @($argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
$cmdLine = "`"$Java`" " + ($quoted -join ' ')

$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
$Log = Join-Path $LogDir "game-$ts.log"
if ($DryRun) {
    "DRYRUN`n$Hdr`nCwd: $GameDir`nDist: $DistDir`nCache: $CacheDir`nLog: $Log`nCmd: $cmdLine"
    exit 0
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$env:PATH = "$DistDir;$env:PATH"   # so neste processo (pzdarwin.dll e dependencias)
if ($DebugGl) { $env:MESA_DEBUG = 'incomplete_fbo,context' }   # so neste processo
foreach ($k in $EnvPairs.Keys) { Set-Item -LiteralPath "Env:$k" -Value $EnvPairs[$k] }   # so neste processo (e filhos)
"$Hdr`nCwd: $GameDir`nCmd: $cmdLine`n" | Set-Content -LiteralPath $Log
$p = Start-Process -FilePath $Java -ArgumentList $quoted -WorkingDirectory $GameDir -PassThru -NoNewWindow `
     -RedirectStandardOutput "$Log.stdout" -RedirectStandardError "$Log.stderr"
$null = $p.Handle
$timedOut = $false
if ($Timeout -gt 0) {
    if (-not $p.WaitForExit($Timeout * 1000)) { $timedOut = $true; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; $p.WaitForExit() }
} else { $p.WaitForExit() }
$code = if ($timedOut) { 124 } else { $p.ExitCode }
"--- stdout ---" | Add-Content -LiteralPath $Log
if (Test-Path "$Log.stdout") { Get-Content "$Log.stdout" | Add-Content -LiteralPath $Log }
"--- stderr ---" | Add-Content -LiteralPath $Log
if (Test-Path "$Log.stderr") { Get-Content "$Log.stderr" | Add-Content -LiteralPath $Log }
Remove-Item "$Log.stdout", "$Log.stderr" -ErrorAction SilentlyContinue
$note = if ($timedOut) { " (timeout de ${Timeout}s, processo encerrado)" } else { '' }
"Codigo de saida: $code$note`nLog: $Log"
exit $code
