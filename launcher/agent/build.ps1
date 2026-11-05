<#
Compila launcher\agent\src\PzArmAgent.java e gera dist\pzarm-agent.jar (JDK 25 ARM64 do spike; java.lang.classfile).
Compila contra projectzomboid.jar (LWJGL e classes do jogo); em execucao elas vem do classpath do jogo.
Uso: .\build.ps1 [-GameDir <p>]
#>
param([string]$GameDir = $(if ($env:PZ_GAME_DIR) { $env:PZ_GAME_DIR } else { 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' }))
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Jdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $Jar)) { Write-Error "Jar nao encontrado: $Jar"; exit 2 }
$Out = Join-Path $Root 'build\agent'
Remove-Item -Recurse -Force $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Out | Out-Null
& (Join-Path $Jdk 'javac.exe') --release 25 -nowarn -cp $Jar -d $Out (Join-Path $PSScriptRoot 'src\PzArmAgent.java') (Join-Path $PSScriptRoot 'src\FpsCounter.java')
if ($LASTEXITCODE -ne 0) { Write-Error 'javac falhou'; exit $LASTEXITCODE }
$mf = Join-Path $Root 'build\agent-MANIFEST.MF'
[IO.File]::WriteAllText($mf, "Premain-Class: PzArmAgent`r`nCan-Redefine-Classes: false`r`nCan-Retransform-Classes: false`r`n")
$Dist = Join-Path $Root 'dist'
& (Join-Path $Jdk 'jar.exe') --create --file (Join-Path $Dist 'pzarm-agent.jar') --manifest $mf -C $Out .
if ($LASTEXITCODE -ne 0) { Write-Error 'jar falhou'; exit $LASTEXITCODE }
Write-Host "ok: $(Join-Path $Dist 'pzarm-agent.jar')"
