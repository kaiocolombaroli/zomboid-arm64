<#
Testa os hooks renderdiag/renderfix do agente (pzarm-agent.jar) com o RingBuffer.drawElements REAL do jogo num contexto GLFW oculto (ARM64).
Uso: .\run-render-test.ps1   (rode antes launcher\agent\build.ps1). Roda 11 vezes: sem fix, rebind, uniforms, dirty, dirtyprog, flush, flush64 (flush a cada 64), flushtex, flushchunk, flushtexchunk, collapse (transformacao real de FBORenderCutaways, sem GL).
Saida: build\render-test\<modo>\render-diag.log. Nada e escrito na pasta do jogo. Codigo de saida != 0 se algum modo falhar.
#>
param([string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid')
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Jdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Dist = Join-Path $Root 'dist'
$Harness = Join-Path $Root 'build\harness'
New-Item -ItemType Directory -Force $Harness | Out-Null
& (Join-Path $Jdk 'javac.exe') -nowarn -cp "$Jar;$Dist\pzarm-agent.jar" -d $Harness (Join-Path $PSScriptRoot 'AgentRenderTest.java')
if ($LASTEXITCODE -ne 0) { Write-Error 'javac falhou'; exit $LASTEXITCODE }
$env:PATH = "$Dist;$env:PATH"
$bad = 0
foreach ($fix in 'none', 'rebind', 'uniforms', 'dirty', 'dirtyprog', 'flush', 'flush64', 'flushtex', 'flushchunk', 'flushtexchunk', 'collapse') {
    $out = Join-Path $Root "build\render-test\$fix"
    New-Item -ItemType Directory -Force $out | Out-Null
    $agent = "renderdiag,diagevery=1,dir=$out"
    if ($fix -eq 'collapse') { $agent = 'collapse=player' }
    elseif ($fix -eq 'flush64') { $agent += ",renderfix=flush,flushevery=64" }
    elseif ($fix -ne 'none') { $agent += ",renderfix=$fix" }
    Write-Host "=== modo: $fix"
    $ErrorActionPreference = 'Continue'
    & (Join-Path $Jdk 'java.exe') -Xmx1500m --enable-native-access=ALL-UNNAMED "-javaagent:$Dist\pzarm-agent.jar=$agent" "-Dtest.fix=$fix" "-Dtest.logdir=$out" `
        "-Djava.library.path=$Dist" "-Dorg.lwjgl.librarypath=$Dist" -cp "$Harness;$Jar" AgentRenderTest 2>&1 | Where-Object { $_ -notmatch '^WARNING: ' }
    if ($LASTEXITCODE -ne 0) { $bad++ }
    $ErrorActionPreference = 'Stop'
}
if ($bad) { Write-Host "FALHARAM $bad modo(s)"; exit 1 }
Write-Host 'TODOS OS MODOS OK'
