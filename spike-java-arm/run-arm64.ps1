<#
Executa o Project Zomboid com o JDK 25 ARM64 do spike, sem escrever na pasta do jogo.
Uso: .\run-arm64.ps1 [-GameDir <pasta>]
#>
param(
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = 'Stop'

# Argumentos da JVM a remover SOMENTE se o JVM recusar (prefixo ou valor exato). Ex.: '-XX:+UseZGC','-agentlib'
$DropArgs = @()

$Here    = $PSScriptRoot
$Java    = Join-Path $Here 'jdk-25-arm64\bin\java.exe'
$CacheDir = Join-Path $Here 'cache'
$LogDir  = Join-Path $Here 'logs'
$JsonPath = Join-Path $GameDir 'ProjectZomboid64.json'

if (-not (Test-Path -LiteralPath $Java)) { Write-Error "java.exe ARM64 nao encontrado: $Java"; exit 2 }
if (-not (Test-Path -LiteralPath $GameDir -PathType Container)) { Write-Error "Pasta do jogo nao existe: $GameDir"; exit 2 }
if (-not (Test-Path -LiteralPath $JsonPath)) { Write-Error "ProjectZomboid64.json nao encontrado em: $GameDir"; exit 2 }

try { $cfg = Get-Content -LiteralPath $JsonPath -Raw | ConvertFrom-Json }
catch { Write-Error "ProjectZomboid64.json invalido: $($_.Exception.Message)"; exit 2 }

foreach ($f in 'mainClass','classpath','vmArgs') {
    if ($null -eq $cfg.$f) { Write-Error "Campo '$f' ausente em $JsonPath"; exit 2 }
}

$vmArgs = @($cfg.vmArgs | Where-Object {
    $a = $_
    -not ($DropArgs | Where-Object { $a -eq $_ -or $a.StartsWith($_) })
})
$Natives = Join-Path $Here 'natives-arm64'
$vmArgs = @($vmArgs) + "-Dorg.lwjgl.librarypath=$Natives"
$cp = (@($cfg.classpath) -join ';')

New-Item -ItemType Directory -Force -Path $CacheDir, $LogDir | Out-Null
$Log = Join-Path $LogDir ("run-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

$argList = @($vmArgs) + @('-cp', $cp, $cfg.mainClass, '-nosteam', "-cachedir=$CacheDir")
"Java: $Java`nGameDir: $GameDir`nArgs: $($argList -join ' ')`n" | Tee-Object -FilePath $Log

$Out = "$Log.stdout"; $Err = "$Log.stderr"
$p = Start-Process -FilePath $Java -ArgumentList $argList -WorkingDirectory $GameDir -PassThru -NoNewWindow -RedirectStandardOutput $Out -RedirectStandardError $Err
$null = $p.Handle
if (-not $p.WaitForExit($TimeoutSec * 1000)) {
    "TIMEOUT ${TimeoutSec}s: matando apenas o PID $($p.Id)" | Tee-Object -FilePath $Log -Append
    Stop-Process -Id $p.Id -Force
    $p.WaitForExit()
    $code = 'timeout-killed'
} else { $code = $p.ExitCode }
"--- stdout ---" | Add-Content $Log; Get-Content $Out -ErrorAction SilentlyContinue | Add-Content $Log
"--- stderr ---" | Add-Content $Log; Get-Content $Err -ErrorAction SilentlyContinue | Add-Content $Log

"Codigo de saida: $code" | Tee-Object -FilePath $Log -Append
if ($code -is [int]) { exit $code } else { exit 124 }
