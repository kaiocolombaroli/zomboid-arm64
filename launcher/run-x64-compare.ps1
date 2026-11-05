<#
Inicia o Project Zomboid com o JRE x64 e as DLLs x64 do proprio jogo (referencia de FPS para o M4).
Uso: .\run-x64-compare.ps1 [-Timeout <s>] [-DryRun] [-GameDir <p>] [-CacheDir <p>]
  -Timeout 0 = sem limite. -DryRun valida tudo e imprime a linha de comando (nao abre o jogo).
Usa o mesmo -cachedir de cache\ do run-game.ps1 (o save la foi criado no ARM64), -nosteam -nosound e o agente em modo fps
(grava logs\fps-amd64-<timestamp>.csv). Nao usa dist\: so as DLLs x64 do jogo. Nada e escrito na pasta do jogo.
Codigos de saida: 2 = erro de ambiente.
  -Env CHAVE=VALOR (varias: -Env A=1,B=2) = variavel de ambiente so no processo do jogo.
  -PzSet PerformanceSettings.fboRenderChunk=false (ou useFbos; varias com virgula) = campo estatico definido pelo agente (precisa do agente).
  Os valores ativos vao para o cabecalho de logs\game-*.log e o agente imprime "[pzarm-agent] pz-set aplicado: ..." no stderr (tambem no log).
#>
param(
    [int]$Timeout = 0,
    [switch]$DryRun,
    [Alias('Env')][string[]]$EnvVars = @(),
    [string[]]$PzSet = @(),
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
    [string]$CacheDir = ''
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $CacheDir) { $CacheDir = Join-Path $Root 'cache' }
$LogDir = Join-Path $Root 'logs'
function Fail([int]$code, [string]$msg) { [Console]::Error.WriteLine("ERRO: $msg"); exit $code }
function Full([string]$p) { [IO.Path]::GetFullPath($p).TrimEnd('\') }

# --- -Env / -PzSet (diagnostico de renderizacao) ---
$EnvPairs = [ordered]@{}
foreach ($e in @($EnvVars | ForEach-Object { $_ -split "," } | Where-Object { $_ })) {
    if ($e -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { Fail 2 "-Env invalido (use CHAVE=VALOR): $e" }
    $EnvPairs[$Matches[1]] = $Matches[2]
}
$PzSets = @()
foreach ($z in @($PzSet | ForEach-Object { $_ -split "," } | Where-Object { $_ })) {
    if ($z -notmatch '^PerformanceSettings\.(fboRenderChunk|useFbos)=(true|false)$') { Fail 2 "-PzSet invalido (permitido: PerformanceSettings.fboRenderChunk|useFbos=true|false): $z" }
    $PzSets += $z
}
$envDesc = if ($EnvPairs.Count) { ($EnvPairs.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ' } else { '(nenhuma)' }
$pzDesc  = if ($PzSets.Count) { $PzSets -join ' ' } else { '(nenhum)' }
$Hdr = "Env (so no processo do jogo): $envDesc`nPzSet (agente Java, aplicado ao fim do <clinit> de PerformanceSettings): $pzDesc`n"

$GameDir = Full $GameDir; $CacheDir = Full $CacheDir
$Java = Join-Path $GameDir 'jre64\bin\java.exe'
$JsonPath = Join-Path $GameDir 'ProjectZomboid64.json'
$AgentJar = Join-Path $Root 'dist\pzarm-agent.jar'
if (-not (Test-Path -LiteralPath $Java))      { Fail 2 "java.exe x64 nao encontrado: $Java" }
if (-not (Test-Path -LiteralPath $JsonPath))  { Fail 2 "ProjectZomboid64.json nao encontrado em: $GameDir" }
if (-not (Test-Path -LiteralPath $AgentJar))  { Fail 2 "agente ausente: $AgentJar (rode launcher\agent\build.ps1)" }
if (($CacheDir + '\').StartsWith($GameDir + '\', [StringComparison]::OrdinalIgnoreCase)) { Fail 2 "cache nao pode ficar dentro da pasta do jogo" }
try { $cfg = Get-Content -LiteralPath $JsonPath -Raw | ConvertFrom-Json }
catch { Fail 2 "ProjectZomboid64.json invalido: $($_.Exception.Message)" }
foreach ($f in 'mainClass','classpath','vmArgs') { if ($null -eq $cfg.$f) { Fail 2 "campo '$f' ausente em $JsonPath" } }
New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

# vmArgs do bloco 'windows' (maior chave <= versao do SO), como o launcher oficial; java.library.path fica o do JSON (win64/;.)
$winArgs = @()
if ($cfg.windows) {
    $osVer = [Environment]::OSVersion.Version
    $best = $null; $bestVer = $null
    foreach ($prop in $cfg.windows.PSObject.Properties) {
        $v = $null
        if ([version]::TryParse($prop.Name, [ref]$v) -and $v -le $osVer -and ($null -eq $bestVer -or $v -gt $bestVer)) { $best = $prop.Value; $bestVer = $v }
    }
    if ($best -and $best.vmArgs) { $winArgs = @($best.vmArgs) }
}
$winArgs = @($winArgs | Where-Object { @($cfg.vmArgs) -notcontains $_ })
$agentMode = "fps,dir=$LogDir"
foreach ($z in $PzSets) { $agentMode += ",pz=$z" }
$cp = (@($cfg.classpath) -join ';')
$argList = @($cfg.vmArgs) + $winArgs + @("-javaagent:$AgentJar=$agentMode", "-XX:ErrorFile=$LogDir\hs_err_%p.log") +
    @('-cp', $cp, $cfg.mainClass, '-nosteam', '-nosound', "-cachedir=$CacheDir")
$quoted = @($argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
$cmdLine = "`"$Java`" " + ($quoted -join ' ')

$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
$Log = Join-Path $LogDir "game-x64-$ts.log"
if ($DryRun) {
    "DRYRUN`n$Hdr`nCwd: $GameDir`nCache: $CacheDir`nLog: $Log`nCmd: $cmdLine"
    exit 0
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
foreach ($k in $EnvPairs.Keys) { Set-Item -LiteralPath "Env:$k" -Value $EnvPairs[$k] }   # so neste processo (e filhos)
"$Hdr`nCwd: $GameDir`nCmd: $cmdLine`n" | Set-Content -LiteralPath $Log
$p = Start-Process -FilePath $Java -ArgumentList $quoted -WorkingDirectory $GameDir -PassThru -NoNewWindow `
     -RedirectStandardOutput "$Log.stdout" -RedirectStandardError "$Log.stderr"
$null = $p.Handle
$timedOut = $false
if ($Timeout -gt 0) {
    if (-not $p.WaitForExit($Timeout * 1000)) { $timedOut = $true; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; $p.WaitForExit() }
} else { $p.WaitForExit() }
$code = if ($timedOut) { 124 } else { $p.ExitCode }
"--- stdout ---" | Add-Content -LiteralPath $Log
if (Test-Path "$Log.stdout") { Get-Content "$Log.stdout" | Add-Content -LiteralPath $Log }
"--- stderr ---" | Add-Content -LiteralPath $Log
if (Test-Path "$Log.stderr") { Get-Content "$Log.stderr" | Add-Content -LiteralPath $Log }
Remove-Item "$Log.stdout", "$Log.stderr" -ErrorAction SilentlyContinue
$note = if ($timedOut) { " (timeout de ${Timeout}s, processo encerrado)" } else { '' }
"Codigo de saida: $code$note`nLog: $Log"
exit $code
