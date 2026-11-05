<#
Gera dist\ (DLLs ARM64 + manifest.json + pzarm-agent.jar) a partir do codigo deste repositorio.
Uso: .\build.ps1 [-GameDir <p>] [-Clean] [-Test] [-SkipCheck]
  -GameDir  pasta do jogo (Windows, Steam). Padrao: variavel PZ_GAME_DIR ou a pasta padrao da Steam. So leitura.
  -Clean    apaga build\ e dist\ antes (a configuracao do CMake e refeita).
  -Test     depois do build roda ctest e os testes Python (leva alguns minutos).
  -SkipCheck  nao roda "setup.ps1 -CheckOnly" antes.
Pre-requisitos: rode .\setup.ps1 antes. Nada e escrito na pasta do jogo.
Codigo de saida: 0 = ok; diferente de 0 = falha (a mensagem diz o passo).
#>
param(
    [string]$GameDir = $(if ($env:PZ_GAME_DIR) { $env:PZ_GAME_DIR } else { 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' }),
    [switch]$Clean,
    [switch]$Test,
    [switch]$SkipCheck
)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
function Fail([string]$m) { [Console]::Error.WriteLine("ERRO: $m"); exit 1 }
function Step([string]$m) { Write-Host "== $m" -ForegroundColor Cyan }

$GameDir = [IO.Path]::GetFullPath($GameDir).TrimEnd('\')
if (-not $SkipCheck) {
    Step 'conferindo pre-requisitos (setup.ps1 -CheckOnly)'
    & (Join-Path $Root 'setup.ps1') -GameDir $GameDir -CheckOnly
    if ($LASTEXITCODE -ne 0) { Fail 'faltam pre-requisitos; resolva as pendencias acima (setup.ps1) e rode de novo.' }
}
$jar = Join-Path $GameDir 'projectzomboid.jar'
if (-not (Test-Path -LiteralPath $jar)) { Fail "projectzomboid.jar nao encontrado em $GameDir" }

$tc = Join-Path $Root 'tools\toolchain'
$cmake = Get-ChildItem (Join-Path $tc 'cmake') -Recurse -Filter cmake.exe -ErrorAction SilentlyContinue | Select-Object -First 1
$ninja = Join-Path $tc 'ninja\ninja.exe'
$llvm = Get-ChildItem (Join-Path $tc 'llvm-mingw') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $cmake -or -not (Test-Path $ninja) -or -not $llvm) { Fail 'toolchain ausente em tools\toolchain (rode setup.ps1).' }
$env:PATH = "$($llvm.FullName)\bin;$($cmake.DirectoryName);$(Split-Path $ninja);$env:PATH"
$env:PZ_GAME_DIR = $GameDir
$env:PZ_JAR = $jar

$build = Join-Path $Root 'build'
$dist = Join-Path $Root 'dist'
if ($Clean) { foreach ($d in $build, $dist) { if (Test-Path $d) { Remove-Item -Recurse -Force $d } } }
New-Item -ItemType Directory -Force $dist | Out-Null

function Cmake-Path([string]$p) { $p -replace '\\', '/' }
Step 'configurando (CMake + Ninja + llvm-mingw ARM64)'
& $cmake.FullName -S (Join-Path $Root 'native') -B $build -G Ninja `
    "-DCMAKE_TOOLCHAIN_FILE=$(Cmake-Path (Join-Path $Root 'native\toolchain-llvm-mingw.cmake'))" `
    "-DCMAKE_MAKE_PROGRAM=$(Cmake-Path $ninja)" `
    "-DPZ_GAME_DIR=$(Cmake-Path $GameDir)" "-DPZ_JAR=$(Cmake-Path $jar)"
if ($LASTEXITCODE -ne 0) { Fail 'cmake (configuracao) falhou' }

Step 'compilando DLLs (pzdarwin, pontes, stubs) e gerando manifest.json'
& $cmake.FullName --build $build
if ($LASTEXITCODE -ne 0) { Fail 'cmake --build falhou' }

Step 'compilando o agente Java (pzarm-agent.jar)'
& (Join-Path $Root 'launcher\agent\build.ps1') -GameDir $GameDir
if ($LASTEXITCODE -ne 0) { Fail 'build do agente falhou' }

# O manifesto e as DLLs ja foram gerados; confere o resultado com um DryRun do launcher.
Step 'conferindo dist\ com o launcher (-DryRun)'
$dry = & (Join-Path $Root 'launcher\run-game.ps1') -DryRun -GameDir $GameDir 2>&1
if ($LASTEXITCODE -ne 0) { $dry | ForEach-Object { Write-Host $_ }; Fail "dist nao passou na conferencia do launcher (codigo $LASTEXITCODE)" }
Write-Host 'launcher -DryRun: ok'

if ($Test) {
    Step 'ctest'
    & (Join-Path $cmake.DirectoryName 'ctest.exe') --test-dir $build --output-on-failure -E launcher_unittest
    if ($LASTEXITCODE -ne 0) { Fail 'ctest falhou' }
    Step 'testes Python'
    Push-Location $Root
    try { & python -m unittest discover -s tests/tools -t . } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Fail 'testes Python falharam' }
}
Write-Host ''
Write-Host "Pronto: $dist" -ForegroundColor Green
Write-Host 'Para jogar: .\launcher\run-game.ps1'
