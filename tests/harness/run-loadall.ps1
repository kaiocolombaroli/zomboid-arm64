<#
Compila (em build\harness) e roda LoadAll no JDK ARM64 do spike: dist\ e o unico java.library.path e vai no
inicio do PATH so deste processo (para as DLLs ponte acharem pzdarwin.dll). Saida esperada: 5 libs, codigo 0.
Nada e escrito na pasta do jogo.
#>
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Jdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
$Out = Join-Path $Root 'build\harness'
$Dist = [IO.Path]::GetFullPath((Join-Path $Root 'dist')).TrimEnd('\')
New-Item -ItemType Directory -Force $Out | Out-Null
& (Join-Path $Jdk 'javac.exe') -d $Out (Join-Path $PSScriptRoot 'LoadAll.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$env:PATH = "$Dist;$env:PATH"   # so no processo
& (Join-Path $Jdk 'java.exe') --enable-native-access=ALL-UNNAMED "-Djava.library.path=$Dist" -cp $Out LoadAll
exit $LASTEXITCODE
