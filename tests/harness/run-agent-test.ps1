<#
Testa launcher\agent (pzarm-agent.jar) com o TextureFBO real do jogo num contexto GLFW oculto (ARM64).
Uso: .\run-agent-test.ps1   (rode antes launcher\agent\build.ps1). Saida: logs\fbo-debug-test.log
MESA_DEBUG so neste processo. Nada e escrito na pasta do jogo.
#>
param([string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid')
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Jdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Dist = Join-Path $Root 'dist'
$Harness = Join-Path $Root 'build\harness'
$Log = Join-Path $Root 'logs\fbo-debug-test.log'
New-Item -ItemType Directory -Force $Harness, (Join-Path $Root 'logs') | Out-Null
& (Join-Path $Jdk 'javac.exe') -nowarn -cp $Jar -d $Harness (Join-Path $PSScriptRoot 'AgentFboTest.java')
if ($LASTEXITCODE -ne 0) { Write-Error 'javac falhou'; exit $LASTEXITCODE }
$env:PATH = "$Dist;$env:PATH"
$env:MESA_DEBUG = 'incomplete_fbo,context'
$ErrorActionPreference = 'Continue'
& (Join-Path $Jdk 'java.exe') -Xmx1500m --enable-native-access=ALL-UNNAMED "-javaagent:$Dist\pzarm-agent.jar=diag,log=$Log" "-Djava.library.path=$Dist" "-Dorg.lwjgl.librarypath=$Dist" -cp "$Harness;$Jar" AgentFboTest 2>&1 | Where-Object { $_ -notmatch '^WARNING: ' }
exit $LASTEXITCODE
