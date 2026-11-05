#requires -Version 5.1
<#
 Mede CPU / GPU / RAM / threads de um processo durante uma sessao.
 Somente leitura. Usa classes CIM Win32_PerfFormattedData_* (independe do idioma do Windows).
 Uso: powershell -ExecutionPolicy Bypass -File .\measure-session.ps1 -Seconds 180
#>
param(
    [string]$ProcessName = 'ProjectZomboid64',
    [int]$Seconds = 180,
    [int]$WarmupSeconds = 0,
    [double]$IntervalSeconds = 2,
    [string]$Out = ''
)
$ErrorActionPreference = 'Continue'
$inv = [Globalization.CultureInfo]::InvariantCulture
$enc = New-Object System.Text.UTF8Encoding($true)
$ProcessName = $ProcessName -replace '\.exe$', ''

$root = $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $Out) { $Out = Join-Path $root "reports\session-$stamp.csv" }
elseif (-not [IO.Path]::IsPathRooted($Out)) { $Out = Join-Path (Get-Location).Path $Out }
$dir = Split-Path -Parent $Out
if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$sumPath = [IO.Path]::ChangeExtension($Out, $null).TrimEnd('.') + '-resumo.txt'

function F([double]$v, [int]$d = 2) { $v.ToString("F$d", $inv) }
function Pct($arr, [double]$p) {
    $a = @($arr | Sort-Object)
    if ($a.Count -eq 0) { return [double]::NaN }
    $i = [math]::Ceiling($p / 100.0 * $a.Count) - 1
    if ($i -lt 0) { $i = 0 }
    [double]$a[$i]
}
function Avg($arr) { $a = @($arr); if ($a.Count -eq 0) { [double]::NaN } else { ($a | Measure-Object -Average).Average } }
function Fx($v, $d = 1) { if ([double]::IsNaN([double]$v)) { 'n/d' } else { F $v $d } }

# --- Espera o processo ---
Write-Host "Aguardando o processo '$ProcessName' aparecer... (Ctrl+C cancela)"
$proc = $null; $waited = 0
while (-not $proc) {
    $proc = Get-Process -Name $ProcessName -ErrorAction SilentlyContinue | Sort-Object StartTime | Select-Object -First 1
    if ($proc) { break }
    Start-Sleep -Seconds 1
    $waited++
    if ($waited % 15 -eq 0) { Write-Host ("  ainda aguardando '{0}' ({1} s)..." -f $ProcessName, $waited) }
}
$procId = $proc.Id
Write-Host ("Processo encontrado: {0} (PID {1}). Aquecimento {2} s + medicao {3} s, amostra a cada {4} s." -f $proc.ProcessName, $procId, $WarmupSeconds, $Seconds, $IntervalSeconds)

$ncpu = [Environment]::ProcessorCount
# Helper nativo (rapido) para CPU por nucleo e RAM livre; evita consultas CIM caras a cada amostra.
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public static class MsHelper {
  [DllImport("ntdll.dll")] static extern int NtQuerySystemInformation(int c, byte[] b, int len, out int ret);
  [StructLayout(LayoutKind.Sequential)] struct MEMSTAT { public uint dwLength, dwMemoryLoad; public ulong totalPhys, availPhys, totalPage, availPage, totalVirt, availVirt, availExt; }
  [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref MEMSTAT m);
  public static long[] Cores(int n) {
    byte[] b = new byte[48 * n]; int r;
    if (NtQuerySystemInformation(8, b, b.Length, out r) != 0) return null;
    int cnt = r / 48; long[] o = new long[cnt * 2];
    for (int i = 0; i < cnt; i++) { long idle = BitConverter.ToInt64(b, i*48), k = BitConverter.ToInt64(b, i*48+8), u = BitConverter.ToInt64(b, i*48+16); o[i*2] = idle; o[i*2+1] = k + u; }
    return o;
  }
  public static double FreeMB() { MEMSTAT m = new MEMSTAT(); m.dwLength = (uint)Marshal.SizeOf(typeof(MEMSTAT)); if (!GlobalMemoryStatusEx(ref m)) return -1; return m.availPhys / 1048576.0; }
}
"@
$prevCores = [MsHelper]::Cores($ncpu + 8)
$pidPrefix = "pid_${procId}_"

# --- Estado ---
$rows = New-Object System.Collections.ArrayList
$prevThr = @{}          # tid -> ms de CPU
$thrSeries = @{}        # tid -> lista de % (por amostra gravada)
$gpuAvail = $true
$engTypes = @{}         # nome engine -> $true (todos vistos)
$sCpu = New-Object System.Collections.ArrayList
$sProc = New-Object System.Collections.ArrayList
$sGpu = New-Object System.Collections.ArrayList
$sFree = New-Object System.Collections.ArrayList
$sCost = New-Object System.Collections.ArrayList
$sampleIdx = 0

$sw = [Diagnostics.Stopwatch]::StartNew()
$prevT = 0.0; $prevProcMs = $null
try { $prevProcMs = $proc.TotalProcessorTime.TotalMilliseconds } catch {}
try { foreach ($t in $proc.Threads) { try { $prevThr[$t.Id] = $t.TotalProcessorTime.TotalMilliseconds } catch {} } } catch {}
Start-Sleep -Milliseconds ([int]($IntervalSeconds * 1000))
$nextTick = $IntervalSeconds
$nextCountdown = 0
$total = $WarmupSeconds + $Seconds
$ended = 'tempo'

while ($sw.Elapsed.TotalSeconds -lt $total + 0.001) {
    $c0 = $sw.Elapsed.TotalMilliseconds
    $now = $c0 / 1000.0
    $dt = $now - $prevT
    try { $proc.Refresh() } catch {}
    if ($proc.HasExited) { $ended = 'processo fechou'; break }

    # Processo: CPU e memoria
    $procPct = [double]::NaN; $ws = 0; $pb = 0
    $thrNow = @{}
    try {
        $pm = $proc.TotalProcessorTime.TotalMilliseconds
        if ($prevProcMs -ne $null -and $dt -gt 0) { $procPct = ($pm - $prevProcMs) / ($dt * 1000.0 * $ncpu) * 100.0 }
        $prevProcMs = $pm
        $ws = [math]::Round($proc.WorkingSet64 / 1MB, 1)
        $pb = [math]::Round($proc.PrivateMemorySize64 / 1MB, 1)
        foreach ($t in $proc.Threads) { try { $thrNow[$t.Id] = $t.TotalProcessorTime.TotalMilliseconds } catch {} }
    } catch {}
    $thrPct = @{}
    foreach ($k in $thrNow.Keys) {
        $d = 0.0
        if ($prevThr.ContainsKey($k) -and $dt -gt 0) { $d = ($thrNow[$k] - $prevThr[$k]) / ($dt * 1000.0) * 100.0; if ($d -lt 0) { $d = 0 } }
        $thrPct[$k] = $d
    }
    $prevThr = $thrNow
    $top = @($thrPct.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 5)

    # CPU total e por nucleo (NtQuerySystemInformation, sem CIM)
    $cpuTot = [double]::NaN; $cores = ''
    try {
        $cc = [MsHelper]::Cores($ncpu + 8)
        if ($cc -and $prevCores -and $cc.Length -eq $prevCores.Length) {
            $list = @(); $sumIdle = 0.0; $sumTot = 0.0
            for ($i = 0; $i -lt $cc.Length; $i += 2) {
                $di = $cc[$i] - $prevCores[$i]; $dtt = $cc[$i+1] - $prevCores[$i+1]
                $sumIdle += $di; $sumTot += $dtt
                if ($dtt -gt 0) { $list += [math]::Round(100.0 * (1.0 - $di / $dtt), 0) } else { $list += 0 }
            }
            if ($sumTot -gt 0) { $cpuTot = 100.0 * (1.0 - $sumIdle / $sumTot) }
            $cores = $list -join ';'
        }
        $prevCores = $cc
    } catch {}

    # RAM livre
    $freeMb = [double]::NaN
    try { $f = [MsHelper]::FreeMB(); if ($f -ge 0) { $freeMb = [math]::Round($f, 0) } } catch {}

    # GPU (uma consulta de engines + uma de memoria)
    $g3 = [double]::NaN; $gOther = [double]::NaN; $gT3 = [double]::NaN; $gTOther = [double]::NaN
    $gded = [double]::NaN; $gsh = [double]::NaN
    if ($gpuAvail) {
        try {
            $eng = @(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction Stop)
            $g3 = 0.0; $gOther = 0.0; $gT3 = 0.0; $gTOther = 0.0
            foreach ($e in $eng) {
                $n = [string]$e.Name
                $is3d = $n -match 'engtype_3D$'
                $u = [double]$e.UtilizationPercentage
                if ($n.StartsWith($pidPrefix)) { if ($is3d) { $g3 += $u } else { $gOther += $u } }
                if ($is3d) { $gT3 += $u } else { $gTOther += $u }
            }
            # 3D total do adaptador: maior valor entre engines 3D nao faz sentido somar por engine; usa soma limitada a 100
            if ($gT3 -gt 100) { $gT3 = 100.0 }
            if ($g3 -gt 100) { $g3 = 100.0 }
            if ($gTOther -gt 100) { $gTOther = 100.0 }
            if ($gOther -gt 100) { $gOther = 100.0 }
        } catch { $gpuAvail = $false }
        try {
            $mem = @(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUAdapterMemory -ErrorAction Stop)
            if ($mem.Count -gt 0) {
                $gded = [math]::Round((($mem | Measure-Object DedicatedUsage -Sum).Sum) / 1MB, 0)
                $gsh = [math]::Round((($mem | Measure-Object SharedUsage -Sum).Sum) / 1MB, 0)
            }
        } catch {}
    }

    $recording = ($now -ge $WarmupSeconds)
    $cost = $sw.Elapsed.TotalMilliseconds - $c0
    if ($recording) {
        $sampleIdx++
        foreach ($k in $thrPct.Keys) {
            if (-not $thrSeries.ContainsKey($k)) {
                $l = New-Object System.Collections.ArrayList
                for ($i = 1; $i -lt $sampleIdx; $i++) { [void]$l.Add(0.0) }
                $thrSeries[$k] = $l
            }
        }
        foreach ($k in @($thrSeries.Keys)) {
            $v = 0.0; if ($thrPct.ContainsKey($k)) { $v = $thrPct[$k] }
            [void]$thrSeries[$k].Add($v)
        }
        if (-not [double]::IsNaN($cpuTot)) { [void]$sCpu.Add($cpuTot) }
        if (-not [double]::IsNaN($procPct)) { [void]$sProc.Add($procPct) }
        if (-not [double]::IsNaN($g3)) { [void]$sGpu.Add($g3) }
        if (-not [double]::IsNaN($freeMb)) { [void]$sFree.Add($freeMb) }
        [void]$sCost.Add($cost)
        $r = [ordered]@{
            horario = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff', $inv)
            t_s = F $now 1
            cpu_total_pct = Fx $cpuTot 1
            cpu_nucleos_pct = $cores
            cpu_proc_pct_maquina = Fx $procPct 1
        }
        for ($i = 0; $i -lt 5; $i++) {
            $tid = ''; $tp = ''
            if ($i -lt $top.Count) { $tid = $top[$i].Key; $tp = F $top[$i].Value 1 }
            $r["thr$($i+1)_id"] = $tid
            $r["thr$($i+1)_pct_nucleo"] = $tp
        }
        $r.gpu3d_proc_pct = Fx $g3 1
        $r.gpu_outras_engines_proc_pct = Fx $gOther 1
        $r.gpu3d_adaptador_pct = Fx $gT3 1
        $r.gpu_outras_engines_adaptador_pct = Fx $gTOther 1
        $r.gpu_mem_dedicada_mb = Fx $gded 0
        $r.gpu_mem_compartilhada_mb = Fx $gsh 0
        $r.ram_livre_mb = Fx $freeMb 0
        $r.proc_working_set_mb = F $ws 1
        $r.proc_private_mb = F $pb 1
        $r.amostra_ms = F $cost 0
        [void]$rows.Add([pscustomobject]$r)
    }

    # Contagem regressiva
    if ($now -ge $nextCountdown) {
        $left = [math]::Max(0, [int]($total - $now))
        $fase = 'medindo'; if (-not $recording) { $fase = 'aquecendo' }
        Write-Host ("  [{0}] faltam ~{1} s | CPU {2}% | proc {3}% | GPU3D(proc) {4}% | RAM livre {5} MB | amostra {6} ms" -f $fase, $left, (Fx $cpuTot 0), (Fx $procPct 0), (Fx $g3 0), (Fx $freeMb 0), (F $cost 0))
        $nextCountdown += 15
    }

    $prevT = $now
    $nextTick += $IntervalSeconds
    $sleep = [int](($nextTick - $sw.Elapsed.TotalSeconds) * 1000)
    if ($sleep -gt 0) { Start-Sleep -Milliseconds $sleep } else { $nextTick = $sw.Elapsed.TotalSeconds }
}

# --- Grava CSV ---
if ($rows.Count -gt 0) {
    $csv = @($rows | ConvertTo-Csv -NoTypeInformation) -join "`r`n"
    [IO.File]::WriteAllText($Out, $csv + "`r`n", $enc)
} else {
    [IO.File]::WriteAllText($Out, "sem amostras`r`n", $enc)
}

# --- Resumo ---
$ranked = @($thrSeries.GetEnumerator() | ForEach-Object { [pscustomobject]@{ Id = $_.Key; Mean = (Avg $_.Value); Vals = $_.Value } } | Sort-Object Mean -Descending)
$lines = New-Object System.Collections.ArrayList
function L($s) { [void]$lines.Add($s) }
L "=== Resumo da sessao: $ProcessName (PID $procId) ==="
L ("Amostras: {0} | intervalo {1} s | fim: {2} | aquecimento descartado: {3} s" -f $rows.Count, $IntervalSeconds, $ended, $WarmupSeconds)
L ("Custo por amostra da medicao: media {0} ms, p95 {1} ms, max {2} ms" -f (Fx (Avg $sCost) 0), (Fx (Pct $sCost 95) 0), (Fx (($sCost | Measure-Object -Maximum).Maximum) 0))
L ("CPU total (maquina):        media {0}%  p95 {1}%" -f (Fx (Avg $sCpu)), (Fx (Pct $sCpu 95)))
L ("CPU do processo (maquina): media {0}%  p95 {1}%   [{2} CPUs logicas]" -f (Fx (Avg $sProc)), (Fx (Pct $sProc 95)), $ncpu)
L ("GPU 3D do processo:        media {0}%  p95 {1}%  p50 {2}%" -f (Fx (Avg $sGpu)), (Fx (Pct $sGpu 95)), (Fx (Pct $sGpu 50)))
L ("RAM livre minima:          {0} MB" -f (Fx (($sFree | Measure-Object -Minimum).Minimum) 0))
$mainP50 = [double]::NaN
for ($i = 0; $i -lt 2; $i++) {
    if ($i -lt $ranked.Count) {
        $th = $ranked[$i]
        $above = @($th.Vals | Where-Object { $_ -gt 90 }).Count
        $frac = 100.0 * $above / [math]::Max(1, $th.Vals.Count)
        $nome = if ($i -eq 0) { 'Thread mais pesada ' } else { 'Segunda thread      ' }
        L ("{0} (TID {1}): media {2}%  p95 {3}%  p50 {4}% de um nucleo | {5}% do tempo acima de 90%" -f $nome, $th.Id, (Fx $th.Mean), (Fx (Pct $th.Vals 95)), (Fx (Pct $th.Vals 50)), (Fx $frac))
        if ($i -eq 0) { $mainP50 = Pct $th.Vals 50 }
    } else { L "Thread #$($i+1): n/d" }
}
$gpuP50 = Pct $sGpu 50
$minFree = ($sFree | Measure-Object -Minimum).Minimum
$gpuHigh = (-not [double]::IsNaN($gpuP50)) -and $gpuP50 -ge 90
$cpuHigh = (-not [double]::IsNaN($mainP50)) -and $mainP50 -ge 90
$v = @()
if ($gpuHigh -and $cpuHigh) { $v += 'AMBOS' }
elseif ($gpuHigh -and $mainP50 -lt 85) { $v += 'GPU' }
elseif ($cpuHigh -and $gpuP50 -lt 85) { $v += 'CPU' }
if ($sFree.Count -gt 0 -and $minFree -lt 300) { $v += 'RAM' }
if ($v.Count -eq 0) { $v = @('INDEFINIDO') }
L ''
L ("VEREDITO: {0}" -f ($v -join ' + '))
L "Limiares: GPU se GPU3D p50 >= 90 e thread principal p50 < 85; CPU se thread principal p50 >= 90 e GPU3D p50 < 85; AMBOS se os dois >= 90; RAM se RAM livre minima < 300 MB (somada as anteriores); senao INDEFINIDO."
L "Nota: 100% de uma thread = uma CPU logica; os nucleos do Surface (Snapdragon) sao heterogeneos (rapidos e eficientes), entao 100% de um nucleo nem sempre equivale em desempenho."
if (-not $gpuAvail) { L "Aviso: contadores de GPU indisponiveis; GPU 3D = n/d." }
L "CSV: $Out"
$lines | ForEach-Object { Write-Host $_ }
[IO.File]::WriteAllText($sumPath, (($lines -join "`r`n") + "`r`n"), $enc)
Write-Host "Resumo gravado em: $sumPath"
