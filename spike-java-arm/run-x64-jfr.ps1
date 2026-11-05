<#
Executa o Project Zomboid com o JRE x64 do proprio jogo, gravando um perfil JFR.
Usa a pasta padrao de saves (sem -cachedir). Sem limite de tempo: feche o jogo para encerrar.
Uso: .\run-x64-jfr.ps1 [-GameDir <pasta>] [-DryRun]
#>
param(
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'

$Here    = $PSScriptRoot
$Java    = Join-Path $GameDir 'jre64\bin\java.exe'
$LogDir  = Join-Path $Here 'logs'
$RepDir  = Join-Path $Here 'reports'
$JsonPath = Join-Path $GameDir 'ProjectZomboid64.json'

if (-not (Test-Path -LiteralPath $Java)) { Write-Error "java.exe x64 nao encontrado: $Java"; exit 2 }
if (-not (Test-Path -LiteralPath $JsonPath)) { Write-Error "ProjectZomboid64.json nao encontrado em: $GameDir"; exit 2 }

try { $cfg = Get-Content -LiteralPath $JsonPath -Raw | ConvertFrom-Json }
catch { Write-Error "ProjectZomboid64.json invalido: $($_.Exception.Message)"; exit 2 }
foreach ($f in 'mainClass','classpath','vmArgs') {
    if ($null -eq $cfg.$f) { Write-Error "Campo '$f' ausente em $JsonPath"; exit 2 }
}

$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $LogDir, $RepDir | Out-Null
$Jfr = Join-Path $RepDir "pz-$ts.jfr"
$Log = Join-Path $LogDir "x64-jfr-$ts.log"

$jfrArgs = @(
    "-XX:StartFlightRecording=name=pz,settings=profile,filename=$Jfr,dumponexit=true,maxsize=500m",
    '-XX:+UnlockDiagnosticVMOptions', '-XX:+DebugNonSafepoints'
)
$cp = (@($cfg.classpath) -join ';')

# vmArgs do bloco 'windows' (chave = versao do SO): usa a maior chave <= versao atual, como o launcher oficial
$winArgs = @()
if ($cfg.windows) {
    $osVer = [Environment]::OSVersion.Version
    $best = $null; $bestVer = $null
    foreach ($prop in $cfg.windows.PSObject.Properties) {
        $v = $null
        if ([version]::TryParse($prop.Name, [ref]$v) -and $v -le $osVer -and ($null -eq $bestVer -or $v -gt $bestVer)) {
            $best = $prop.Value; $bestVer = $v
        }
    }
    if ($best -and $best.vmArgs) { $winArgs = @($best.vmArgs) }
}
# Sem duplicar flags ja presentes em vmArgs de topo
$winArgs = @($winArgs | Where-Object { @($cfg.vmArgs) -notcontains $_ })
$argList = @($cfg.vmArgs) + $winArgs + $jfrArgs + @('-cp', $cp, $cfg.mainClass, '-nosteam')

# Argumentos com espaco precisam de aspas para Start-Process
$quoted = @($argList | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
$cmdLine = "`"$Java`" " + ($quoted -join ' ')

if ($DryRun) {
    "DRYRUN`nCwd: $GameDir`nJFR: $Jfr`nLog: $Log`nCmd: $cmdLine"
    exit 0
}

"Cwd: $GameDir`nCmd: $cmdLine`n" | Tee-Object -FilePath $Log
$p = Start-Process -FilePath $Java -ArgumentList $quoted -WorkingDirectory $GameDir -PassThru -NoNewWindow `
     -RedirectStandardOutput "$Log.stdout" -RedirectStandardError "$Log.stderr"
$null = $p.Handle
$p.WaitForExit()
"--- stdout ---" | Add-Content $Log; Get-Content "$Log.stdout" -ErrorAction SilentlyContinue | Add-Content $Log
"--- stderr ---" | Add-Content $Log; Get-Content "$Log.stderr" -ErrorAction SilentlyContinue | Add-Content $Log
"Codigo de saida: $($p.ExitCode)`nJFR: $Jfr" | Tee-Object -FilePath $Log -Append
exit $p.ExitCode
