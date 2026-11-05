<#
Resume um arquivo JFR usando o jfr.exe do JDK ARM64. Saida: reports\jfr-resumo.txt
Uso: .\read-jfr.ps1 <arquivo.jfr>
#>
param([Parameter(Mandatory)][string]$File)
$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot
$Jfr  = Join-Path $Here 'jdk-25-arm64\bin\jfr.exe'
$Out  = Join-Path $Here 'reports\jfr-resumo.txt'
if (-not (Test-Path -LiteralPath $Jfr))  { Write-Error "jfr.exe nao encontrado: $Jfr"; exit 2 }
if (-not (Test-Path -LiteralPath $File)) { Write-Error "Arquivo nao encontrado: $File"; exit 2 }
$File = (Resolve-Path -LiteralPath $File).Path
New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null

function Get-Events([string]$ev) { (& $Jfr print --events $ev $File 2>$null) -join "`n" }
function Get-Blocks([string]$text, [string]$ev) {
    @([regex]::Matches($text, "(?s)$([regex]::Escape($ev))\s*\{(.*?)\n\}") | ForEach-Object { $_.Groups[1].Value })
}
function Get-TopFrame([string]$b) {
    $m = [regex]::Match($b, '(?s)stackTrace = \[\s*\n\s*([^\n]+)')
    if ($m.Success) { ($m.Groups[1].Value -replace '\s+line:.*$','' -replace '\s+$','') } else { '(sem stack)' }
}
function Get-ThreadName([string]$b) {
    $m = [regex]::Match($b, '(?m)(?:sampledThread|eventThread) = "([^"]*)"')
    if ($m.Success) { $m.Groups[1].Value } else { '(?)' }
}
function Get-Ms([string]$s) {
    $m = [regex]::Match($s, '([\d.,]+)\s*(ns|us|ms|s|m)\b')
    if (-not $m.Success) { return 0.0 }
    $v = [double]($m.Groups[1].Value -replace ',', '.')
    switch ($m.Groups[2].Value) { 'ns' { $v/1e6 } 'us' { $v/1e3 } 'ms' { $v } 's' { $v*1e3 } 'm' { $v*6e4 } }
}
function Summarize-Samples([string]$ev) {
    $bl = Get-Blocks (Get-Events $ev) $ev
    "Total de amostras: $($bl.Count)"
    if ($bl.Count -eq 0) { return }
    $rows = @($bl | ForEach-Object { [pscustomobject]@{ Thread = (Get-ThreadName $_); Method = (Get-TopFrame $_) } })
    ""
    "-- Top 30 metodos (topo da pilha), geral --"
    $rows | Group-Object Method | Sort-Object Count -Descending | Select-Object -First 30 |
        ForEach-Object { '{0,7}  {1}' -f $_.Count, $_.Name }
    ""
    "-- Top 30 metodos por thread (8 threads com mais amostras) --"
    $rows | Group-Object Thread | Sort-Object Count -Descending | Select-Object -First 8 | ForEach-Object {
        "[thread] $($_.Name)  ($($_.Count) amostras)"
        $_.Group | Group-Object Method | Sort-Object Count -Descending | Select-Object -First 30 |
            ForEach-Object { '{0,7}  {1}' -f $_.Count, $_.Name }
    }
    ""
    "-- Amostras por thread --"
    $rows | Group-Object Thread | Sort-Object Count -Descending | ForEach-Object { '{0,7}  {1}' -f $_.Count, $_.Name }
}

$o = New-Object System.Collections.Generic.List[string]
$o.Add("Resumo JFR: $File  ($(Get-Date -Format s))")
$o.Add("")
$o.Add("===== jdk.ExecutionSample (Java) =====")
Summarize-Samples 'jdk.ExecutionSample' | ForEach-Object { $o.Add($_) }
$o.Add("")
$o.Add("===== jdk.NativeMethodSample =====")
Summarize-Samples 'jdk.NativeMethodSample' | ForEach-Object { $o.Add($_) }

$o.Add("")
$o.Add("===== GC =====")
$gb = Get-Blocks (Get-Events 'jdk.GarbageCollection') 'jdk.GarbageCollection'
$sumMs = 0.0; $maxPause = 0.0
foreach ($b in $gb) {
    $m = [regex]::Match($b, 'sumOfPauses = ([^\n]+)'); if ($m.Success) { $sumMs += (Get-Ms $m.Groups[1].Value) }
    $m = [regex]::Match($b, 'longestPause = ([^\n]+)'); if ($m.Success) { $x = Get-Ms $m.Groups[1].Value; if ($x -gt $maxPause) { $maxPause = $x } }
}
$o.Add("Coletas: $($gb.Count)  Pausa total: $([math]::Round($sumMs,1)) ms  Maior pausa: $([math]::Round($maxPause,1)) ms")
$pb = Get-Blocks (Get-Events 'jdk.GCPhasePause') 'jdk.GCPhasePause'
$pms = 0.0
foreach ($b in $pb) { $m = [regex]::Match($b, 'duration = ([^\n]+)'); if ($m.Success) { $pms += (Get-Ms $m.Groups[1].Value) } }
$o.Add("Fases de pausa (jdk.GCPhasePause): $($pb.Count)  soma: $([math]::Round($pms,1)) ms")

$o.Add("")
$o.Add("===== Alocacao por classe (jdk.ObjectAllocationSample, soma de weight) =====")
$ab = Get-Blocks (Get-Events 'jdk.ObjectAllocationSample') 'jdk.ObjectAllocationSample'
if ($ab.Count -eq 0) { $o.Add('(sem eventos de alocacao)') } else {
    $unit = @{ 'bytes' = 1; 'kB' = 1e3; 'MB' = 1e6; 'GB' = 1e9 }
    $rows = @($ab | ForEach-Object {
        $c = [regex]::Match($_, 'objectClass = ([^\s(]+)').Groups[1].Value
        $w = [regex]::Match($_, 'weight = ([\d.,]+)\s*(bytes|kB|MB|GB)')
        $v = 0.0
        if ($w.Success) { $v = [double]($w.Groups[1].Value -replace ',', '.') * $unit[$w.Groups[2].Value] }
        [pscustomobject]@{ Class = $c; Bytes = $v }
    })
    $rows | Group-Object Class | ForEach-Object {
        [pscustomobject]@{ Class = $_.Name; MB = [math]::Round((($_.Group | Measure-Object Bytes -Sum).Sum) / 1e6, 1) }
    } | Sort-Object MB -Descending | Select-Object -First 30 | ForEach-Object { $o.Add(('{0,10} MB  {1}' -f $_.MB, $_.Class)) }
}
$o | Set-Content -LiteralPath $Out -Encoding UTF8
"Resumo salvo em: $Out"
