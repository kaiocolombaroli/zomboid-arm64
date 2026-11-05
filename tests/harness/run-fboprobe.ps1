<#
Roda a sonda de FBO (FboProbe) com janela GLFW oculta e grava reports\fbo-probe-<arch>-s<samples>.txt.
Uso: .\run-fboprobe.ps1 -Arch x64|arm64 [-Samples 0] [-NoDebugCtx] [-GameDir <pasta>]
  x64   : jre64 do jogo; as DLLs LWJGL sao extraidas do jar do jogo pelo proprio LWJGL para build\fbo-x64-lwjgl (org.lwjgl.librarypath).
  arm64 : JDK ARM64 do spike; DLLs ARM64 de dist\ (org.lwjgl.librarypath e java.library.path).
MESA_DEBUG=incomplete_fbo e definido so neste processo (Mesa explica FBO incompleto). Nada e escrito na pasta do jogo.
#>
param(
    [Parameter(Mandatory)][ValidateSet('x64','arm64')][string]$Arch,
    [int]$Samples = 0,
    [switch]$NoDebugCtx,
    [switch]$Stress,
    [switch]$GameClasses,
    [int]$HogMb = 0,
    [int]$StressCapMb = 1500,
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Harness = Join-Path $Root 'build\harness'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Src = Join-Path $PSScriptRoot 'FboProbe.java'
$tag = if ($Stress) { "-stress" } elseif ($GameClasses) { "-game" } else { "" }
if ($HogMb -gt 0) { $tag += "-hog$HogMb" }
$Out = Join-Path $Root "reports\fbo-probe-$Arch-s$Samples$tag.txt"
$Logs = Join-Path $Root 'logs'
$ArmJdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
if (-not (Test-Path -LiteralPath $Jar)) { Write-Error "Jar nao encontrado: $Jar"; exit 2 }
New-Item -ItemType Directory -Force (Join-Path $Root 'reports'), $Harness, $Logs | Out-Null
$cls = Join-Path $Harness 'FboProbe.class'
if (-not (Test-Path $cls) -or (Get-Item $cls).LastWriteTime -lt (Get-Item $Src).LastWriteTime) {
    & (Join-Path $ArmJdk 'javac.exe') -nowarn -cp $Jar -d $Harness $Src
    if ($LASTEXITCODE -ne 0) { Write-Error "javac falhou"; exit $LASTEXITCODE }
}
if ($Arch -eq 'x64') {
    $Java = Join-Path $GameDir 'jre64\bin\java.exe'
    $LibDir = Join-Path $Root 'build\fbo-x64-lwjgl'
    New-Item -ItemType Directory -Force $LibDir | Out-Null
    $Cwd = $Root
} else {
    $Java = Join-Path $ArmJdk 'java.exe'
    $LibDir = Join-Path $Root 'dist'
    $Cwd = $Root
    $env:PATH = "$LibDir;$env:PATH"
}
$env:MESA_DEBUG = 'incomplete_fbo'
$dbg = if ($NoDebugCtx) { '0' } else { '1' }
$errFile = [IO.Path]::GetTempFileName()
Push-Location $Cwd
try {
    $ErrorActionPreference = 'Continue'
    & $Java -Xmx3000m --enable-native-access=ALL-UNNAMED "-XX:ErrorFile=$Logs\hs_err_fboprobe-$Arch-%p.log" "-Djava.library.path=$LibDir" "-Dorg.lwjgl.librarypath=$LibDir" "-Dorg.lwjgl.system.SharedLibraryExtractPath=$LibDir" -cp "$Harness;$Jar" FboProbe $Out $Samples $dbg $(if ($Stress) { 'stress' } elseif ($GameClasses) { 'game' } else { 'nostress' }) $StressCapMb $HogMb 2>$errFile
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
} finally { Pop-Location }
Get-Content $errFile | Where-Object { $_ -notmatch '^WARNING: ' } | Set-Content "$Out.stderr.txt"
Remove-Item $errFile -ErrorAction SilentlyContinue
if ($code -ne 0) { Write-Error "sonda saiu com $code"; exit $code }
Write-Host "ok: $Out"
exit 0
