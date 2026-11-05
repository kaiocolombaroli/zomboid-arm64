<#
Roda o StubHarness contra uma DLL stub de dist\ com a JVM ARM64 do spike.
Uso: .\run-stub.ps1 -Lib ZNetNoSteam64 [-Out <arquivo>]
java.library.path = dist\ (unico); nada e escrito na pasta do jogo. Le build\generated\<lib>_methods.tsv.
#>
param(
    [Parameter(Mandatory)][string]$Lib,
    [string]$Out,
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Harness = Join-Path $Root 'build\harness'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Src = Join-Path $PSScriptRoot 'StubHarness.java'
$Tsv = Join-Path $Root "build\generated\${Lib}_methods.tsv"
if (-not $Out) { $Out = Join-Path $Root "reports\stub-$Lib.txt" }
$ArmJdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
New-Item -ItemType Directory -Force (Join-Path $Root 'reports'), $Harness, (Join-Path $Root 'logs') | Out-Null
$cls = Join-Path $Harness 'StubHarness.class'
if (-not (Test-Path $cls) -or (Get-Item $cls).LastWriteTime -lt (Get-Item $Src).LastWriteTime) {
    & (Join-Path $ArmJdk 'javac.exe') -nowarn -cp $Jar -d $Harness $Src
    if ($LASTEXITCODE -ne 0) { Write-Error 'javac falhou'; exit $LASTEXITCODE }
}
$Dist = Join-Path $Root 'dist'
$env:PATH = "$Dist;$env:PATH"
$crash = Join-Path $Root "logs\hs_err_stub-$Lib-%p.log"
Push-Location $Root
try {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $ArmJdk 'java.exe') --enable-native-access=ALL-UNNAMED "-XX:ErrorFile=$crash" "-Djava.library.path=$Dist" -cp "$Harness;$Jar" StubHarness $Lib $Tsv $Out 2>&1 | Where-Object { $_ -notmatch '^WARNING: ' }
    $code = $LASTEXITCODE
} finally { Pop-Location }
exit $code
