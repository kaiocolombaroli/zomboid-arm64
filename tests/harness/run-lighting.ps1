<#
Roda o LightingHarness e grava reports/lighting-<arch>.txt (+ tempos em reports/lighting-time-<arch>.txt).
Uso: .\run-lighting.ps1 -Arch x64|arm64 [-GameDir <pasta>]
  x64   : jre64 do jogo, cwd = pasta do jogo, java.library.path=.
  arm64 : JDK ARM64 do spike, java.library.path = dist\ (unico); dist\ vai no inicio do PATH so deste processo
          (para a Lighting64.dll achar pzdarwin.dll)
Grava tambem reports/lighting-<arch>.meta (os.arch e java.version), fora do .txt comparado.
Nada e escrito na pasta do jogo.
#>
param(
    [Parameter(Mandatory)][ValidateSet('x64','arm64')][string]$Arch,
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Harness = Join-Path $Root 'build\harness'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Out = Join-Path $Root "reports\lighting-$Arch.txt"
$Times = Join-Path $Root "reports\lighting-time-$Arch.txt"
$Meta = Join-Path $Root "reports\lighting-$Arch.meta"
if (-not (Test-Path (Join-Path $Harness 'LightingHarness.class'))) { Write-Error "Compile primeiro (build\harness)"; exit 2 }
if (-not (Test-Path -LiteralPath $Jar)) { Write-Error "Jar nao encontrado: $Jar"; exit 2 }
New-Item -ItemType Directory -Force (Join-Path $Root 'reports') | Out-Null

if ($Arch -eq 'x64') {
    $Java = Join-Path $GameDir 'jre64\bin\java.exe'
    $LibPath = '.'
    $Cwd = $GameDir
} else {
    $Java = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin\java.exe'
    $LibPath = Join-Path $Root 'dist'
    $Cwd = $Root
    $full = [IO.Path]::GetFullPath($LibPath).TrimEnd('\')
    $env:PATH = "$full;$env:PATH"   # so no processo do harness
    if ($full.StartsWith($GameDir.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) { Write-Error "java.library.path aponta para a pasta do jogo"; exit 2 }
}
if (-not (Test-Path -LiteralPath $Java)) { Write-Error "java.exe nao encontrado: $Java"; exit 2 }

# os.arch e java.version vao para um .meta separado, para o .txt continuar comparavel com compare.py
$ErrorActionPreference = 'Continue'
$props = & $Java -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" } |
    Where-Object { $_ -match '^\s*(os\.arch|java\.version) = ' } | ForEach-Object { $_.Trim() }
$ErrorActionPreference = 'Stop'
[IO.File]::WriteAllLines($Meta, [string[]]@($props))
$props | ForEach-Object { Write-Host $_ }

Remove-Item -ErrorAction SilentlyContinue $Out, $Times
Push-Location $Cwd
try {
    & $Java --enable-native-access=ALL-UNNAMED "-Djava.library.path=$LibPath" -cp "$Harness;$Jar" LightingHarness $Out $Times
    $code = $LASTEXITCODE
} finally { Pop-Location }
if ($code -ne 0) { Write-Error "harness saiu com $code"; exit $code }
Write-Host "ok: $Out"
if (Test-Path $Times) { Get-Content $Times }
exit 0
