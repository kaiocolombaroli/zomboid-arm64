<#
Roda GlRepro (reprodutor isolado do bug de textura do fboRenderChunk) em ARM64 com MESA_GLTHREAD=false e =true
(variavel so no processo filho). Saida combinada: reports\m7-repro.txt; imagens: reports\repro\.
Uso: .\run-glrepro.ps1 [-Sprites 3000] [-Rounds 3] [-Frames 1] [-Mip]
#>
param([int]$Sprites = 3000, [int]$Rounds = 3, [int]$Frames = 1, [switch]$Mip, [switch]$Churn, [switch]$Ring, [switch]$Chunks, [int]$PerRound = 16, [string]$Out = '', [switch]$V3, [string[]]$Factors = @('1', '2', '12', '123'), [int]$Pages = 200, [int]$PageSize = 2048, [int]$MemMB = 1600,
      [switch]$Probe, [string[]]$ExtraEnv = @(),
      [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid')
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Harness = Join-Path $Root 'build\harness'
$Jar = Join-Path $GameDir 'projectzomboid.jar'
$ArmJdk = Join-Path $Root 'spike-java-arm\jdk-25-arm64\bin'
$Dist = Join-Path $Root 'dist'
$Src = Join-Path $PSScriptRoot 'GlRepro.java'
$Final = if ($Out) { $Out } else { Join-Path $Root 'reports\m7-repro.txt' }
$ImgDir = Join-Path $Root 'reports\repro'
New-Item -ItemType Directory -Force $Harness, $ImgDir, (Join-Path $Root 'logs') | Out-Null
& (Join-Path $ArmJdk 'javac.exe') -nowarn -cp $Jar -d $Harness $Src
if ($LASTEXITCODE -ne 0) { Write-Error 'javac falhou'; exit $LASTEXITCODE }
$env:PATH = "$Dist;$env:PATH"

# env extra (ex.: LIBGL_ALWAYS_SOFTWARE=1) so no processo java filho
$saved = @{}
foreach ($kv in $ExtraEnv) { $n, $v = $kv -split '=', 2; $saved[$n] = [Environment]::GetEnvironmentVariable($n); Set-Item -LiteralPath "Env:$n" -Value $v }
function Restore-Env { foreach ($n in $saved.Keys) { if ($null -eq $saved[$n]) { Remove-Item "Env:\$n" -ErrorAction SilentlyContinue } else { Set-Item -LiteralPath "Env:$n" -Value $saved[$n] } } }
$JavaExe = Join-Path $ArmJdk 'java.exe'
if ($Probe) {
    $ErrorActionPreference = 'Continue'
    $o = & $JavaExe -Xmx512m --enable-native-access=ALL-UNNAMED "-Djava.library.path=$Dist" "-Dorg.lwjgl.librarypath=$Dist" -Dprobe=1 -cp "$Harness;$Jar" GlRepro (Join-Path $Root 'build\probe.txt') probe $ImgDir 2>&1 | Where-Object { $_ -notmatch '^WARNING: ' }
    $o | Out-Host
    Restore-Env
    exit 0
}
if ($V3) {
    $ErrorActionPreference = 'Continue'
    Set-Content $Final "# m7-repro-v3 (run-glrepro.ps1 -V3) sprites=$Sprites rounds=$Rounds perRound=$PerRound pages=$Pages pageSize=$PageSize memMB=$MemMB extraEnv=$($ExtraEnv -join ',')"
    $env:MESA_GLTHREAD = 'false'
    foreach ($f in $Factors) {
        $tmp = Join-Path $Root "build\glrepro-v3-$f.txt"
        Remove-Item $tmp -ErrorAction SilentlyContinue
        Write-Host "== fatores $f =="
        & $JavaExe -Xmx2000m --enable-native-access=ALL-UNNAMED "-XX:ErrorFile=$Root\logs\hs_err_glrepro-%p.log" `
            "-Djava.library.path=$Dist" "-Dorg.lwjgl.librarypath=$Dist" -Dbuf=ring -Dv3=1 "-Dfactors=$f" "-Dpages=$Pages" "-Dpsize=$PageSize" "-Dmemmb=$MemMB" "-DperRound=$PerRound" `
            -cp "$Harness;$Jar" GlRepro $tmp "v3-f$f" $ImgDir $Sprites $Rounds 2>&1 | Where-Object { $_ -notmatch '^WARNING: ' } | Out-Host
        Add-Content $Final ''
        if (Test-Path $tmp) { Get-Content $tmp | Add-Content $Final } else { Add-Content $Final "(sem saida para fatores=$f)" }
        if ((Test-Path $tmp) -and (Select-String -Path $tmp -Pattern 'bug REPRODUZIDO' -Quiet)) { Add-Content $Final "PARADO: fatores $f reproduziu"; break }
    }
    Remove-Item Env:\MESA_GLTHREAD -ErrorAction SilentlyContinue
    Restore-Env
    Write-Host "ok: $Final"
    exit 0
}
Set-Content $Final "# m7-repro (gerado por run-glrepro.ps1) sprites=$Sprites rounds=$Rounds frames=$Frames mip=$Mip churn=$Churn ring=$Ring chunks=$Chunks perRound=$PerRound"
foreach ($gt in 'false', 'true') {
    $env:MESA_GLTHREAD = $gt
    $tmp = Join-Path $Root "build\glrepro-glthread-$gt.txt"
    $mipv = if ($Mip) { '1' } else { '0' }
    $churnv = if ($Churn) { '1' } else { '0' }
    $bufv = if ($Ring) { 'ring' } else { 'old' }
    $chunksv = if ($Chunks) { '1' } else { '0' }
    $ErrorActionPreference = 'Continue'
    & (Join-Path $ArmJdk 'java.exe') -Xmx2000m --enable-native-access=ALL-UNNAMED "-XX:ErrorFile=$Root\logs\hs_err_glrepro-%p.log" `
        "-Djava.library.path=$Dist" "-Dorg.lwjgl.librarypath=$Dist" "-Dframes=$Frames" "-Dmip=$mipv" "-Dchurn=$churnv" "-Dbuf=$bufv" "-Dchunks=$chunksv" "-DperRound=$PerRound" `
        -cp "$Harness;$Jar" GlRepro $tmp "glthread-$gt" $ImgDir $Sprites $Rounds 2>&1 | Where-Object { $_ -notmatch '^WARNING: ' } | Out-Host
    $ErrorActionPreference = 'Stop'
    Add-Content $Final ''
    if (Test-Path $tmp) { Get-Content $tmp | Add-Content $Final } else { Add-Content $Final "(sem saida para MESA_GLTHREAD=$gt)" }
}
Remove-Item Env:\MESA_GLTHREAD
Write-Host "ok: $Final"
