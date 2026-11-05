<#
Roda um harness nativo (Clipper, PathFind, ...) e grava reports/native-<lib>-<arch>.txt
(+ tempos em reports/native-<lib>-time-<arch>.txt e metadados os.arch/java.version em reports/native-<lib>-<arch>.meta).
Uso: .\run-native.ps1 -Lib clipper|pathfind|popman|bullet|jassimp|fmod -Arch x64|arm64 [-GameDir <pasta>] [-Audible]
  x64   : jre64 do jogo, cwd = pasta do jogo, java.library.path=.
  arm64 : JDK ARM64 do spike, java.library.path = dist\ (unico); dist\ vai no inicio do PATH so deste processo.
  fmod  : arm64 usa build\fmod-dist\ (fmodintegration64.dll e pzdarwin.dll de dist\ + as DLLs FMOD ARM64 de third-party\fmod\);
          -Audible toca de verdade (sem silenciar o grupo master) e grava reports\native-fmod-<arch>-audible.txt.
Compila o harness em build\harness com o javac do JDK ARM64 se o .class estiver ausente ou mais antigo que o .java.
Logs de crash (hs_err) vao para logs\. Nada e escrito na pasta do jogo.
#>
param(
    [Parameter(Mandatory)][ValidateSet('clipper','pathfind','popman','bullet','jassimp','fmod')][string]$Lib,
    [Parameter(Mandatory)][ValidateSet('x64','arm64')][string]$Arch,
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
    [switch]$Audible
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Harness = Join-Path $Root 'build\harness'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$Class = @{ clipper = 'ClipperHarness'; pathfind = 'PathFindHarness'; popman = 'PopManHarness'; bullet = 'BulletHarness'; jassimp = 'JassimpHarness'; fmod = 'FmodHarness' }[$Lib]
$Src = Join-Path $PSScriptRoot "$Class.java"
$Sfx = if ($Audible) { '-audible' } else { '' }
if ($Audible -and $Lib -ne 'fmod') { Write-Error "-Audible so existe para -Lib fmod"; exit 2 }
$Out = Join-Path $Root "reports\native-$Lib-$Arch$Sfx.txt"
$Times = Join-Path $Root "reports\native-$Lib-time-$Arch$Sfx.txt"
$Meta = Join-Path $Root "reports\native-$Lib-$Arch.meta"
$Logs = Join-Path $Root 'logs'
$ArmJdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
if (-not (Test-Path -LiteralPath $Src)) { Write-Error "Fonte nao encontrado: $Src"; exit 2 }
if (-not (Test-Path -LiteralPath $Jar)) { Write-Error "Jar nao encontrado: $Jar"; exit 2 }
New-Item -ItemType Directory -Force (Join-Path $Root 'reports'), $Harness, $Logs | Out-Null

$cls = Join-Path $Harness "$Class.class"
if (-not (Test-Path $cls) -or (Get-Item $cls).LastWriteTime -lt (Get-Item $Src).LastWriteTime) {
    & (Join-Path $ArmJdk 'javac.exe') -nowarn -cp $Jar -d $Harness $Src
    if ($LASTEXITCODE -ne 0) { Write-Error "javac falhou"; exit $LASTEXITCODE }
}

if ($Arch -eq 'x64') {
    $Java = Join-Path $GameDir 'jre64\bin\java.exe'
    $LibPath = '.'
    $Cwd = $GameDir
} else {
    $Java = Join-Path $ArmJdk 'java.exe'
    $LibPath = Join-Path $Root 'dist'
    if ($Lib -eq 'fmod') {
        # dist\ tem fmod.dll/fmodstudio.dll vazias ate a Tarefa 4: o harness usa uma pasta propria com as DLLs reais.
        $LibPath = Join-Path $Root 'build\fmod-dist'
        New-Item -ItemType Directory -Force $LibPath | Out-Null
        foreach ($f in @(@('dist','fmodintegration64.dll'), @('dist','pzdarwin.dll'), @('third-party\fmod','fmod.dll'), @('third-party\fmod','fmodstudio.dll'))) {
            $src = Join-Path $Root (Join-Path $f[0] $f[1])
            if (-not (Test-Path -LiteralPath $src)) { Write-Error "Arquivo ausente: $src"; exit 2 }
            $dst = Join-Path $LibPath $f[1]
            if (-not (Test-Path -LiteralPath $dst) -or (Get-FileHash $src).Hash -ne (Get-FileHash $dst).Hash) { Copy-Item -Force $src $dst }
        }
        $env:PZARM_DYLIB_OVERRIDE = Join-Path $Root 'game-libs\macos-42.21\libfmodintegration.dylib'
    }
    $Cwd = $Root
    $full = [IO.Path]::GetFullPath($LibPath).TrimEnd('\')
    $env:PATH = "$full;$env:PATH"   # so no processo do harness (pzdarwin.dll)
    if ($full.StartsWith($GameDir.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) { Write-Error "java.library.path aponta para a pasta do jogo"; exit 2 }
}
if (-not (Test-Path -LiteralPath $Java)) { Write-Error "java.exe nao encontrado: $Java"; exit 2 }

$ErrorActionPreference = 'Continue'
$props = & $Java -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" } |
    Where-Object { $_ -match '^\s*(os\.arch|java\.version) = ' } | ForEach-Object { $_.Trim() }
$ErrorActionPreference = 'Stop'
[IO.File]::WriteAllLines($Meta, [string[]]@($props))
$props | ForEach-Object { Write-Host $_ }

Remove-Item -ErrorAction SilentlyContinue $Out, $Times
$errFile = [IO.Path]::GetTempFileName()
Push-Location $Cwd
try {
    # hs_err vai para logs\ (nunca para a pasta do jogo, mesmo no x64 onde o cwd e a pasta do jogo)
    $crash = Join-Path $Logs "hs_err_native-$Lib-$Arch-%p.log"
    $ErrorActionPreference = 'Continue'
    & $Java --enable-native-access=ALL-UNNAMED "-XX:ErrorFile=$crash" "-Djava.library.path=$LibPath" "-Dpz.game=$GameDir" "-Dpz.tmp=$Harness" "-Dpz.audible=$(if ($Audible) { '1' } else { '0' })" -cp "$Harness;$Jar" $Class $Out $Times 2>$errFile
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
} finally { Pop-Location }
Get-Content $errFile | Where-Object { $_ -notmatch '^WARNING: ' } | ForEach-Object { Write-Host $_ }
Remove-Item $errFile -ErrorAction SilentlyContinue
if ($code -ne 0) { Write-Error "harness saiu com $code"; exit $code }
Write-Host "ok: $Out"
if (Test-Path $Times) { Get-Content $Times }
exit 0
