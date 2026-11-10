<#
Prepara os insumos para montar o port. Roda no Windows on ARM (ARM64), PowerShell 5.1 ou 7.
Uso: .\setup.ps1 [-GameDir <p>] [-MacDepotDir <p>] [-FmodApiDir <p>] [-JdkZip <zip>] [-LwjglNativesDir <p>] [-CheckOnly] [-NoDownload]

Este script nunca escreve na pasta do jogo e nunca baixa o jogo nem o FMOD (cada pessoa usa a propria conta).
  1. Confere: Windows ARM64, Python 3, jogo 42.21 (projectzomboid.jar), Compatibility Pack do GL.
  2. Baixa o que e livre: JDK 25 ARM64 (Microsoft Build of OpenJDK), LWJGL 3.4.1 natives windows-arm64 (Maven Central),
     toolchain (llvm-mingw, CMake, Ninja; via tools\setup-toolchain.ps1).
  3. Copia o que voce baixou: dylibs do depot macOS 108602 (-MacDepotDir) para game-libs\macos-42.21,
     fmod.dll/fmodstudio.dll ARM64 (-FmodApiDir) para third-party\fmod.
  -CheckOnly = so verifica e relata; nao baixa nem copia nada.
  -NoDownload = nao acessa a rede (use com -JdkZip e com a toolchain ja instalada).
  -GameDir = pasta do jogo para Windows (Steam). Padrao: variavel PZ_GAME_DIR ou a pasta padrao da Steam.
  -JdkZip / -LwjglNativesDir = usam o zip do JDK ou as 6 DLLs LWJGL ARM64 que voce ja tem (evita o download).
  -MacDepotDir = pasta onde voce baixou o depot 108602 (a busca acha ...\Contents\Java\*.dylib; ou a propria pasta das dylibs).
  -FmodApiDir = pasta "FMOD Studio API Windows" (a que tem api\core e api\studio) da instalacao do FMOD Studio 2.03.09.
Codigo de saida: 0 = tudo pronto; 1 = falta algo (a lista de pendencias aparece no fim).
#>
param(
    [string]$GameDir = $(if ($env:PZ_GAME_DIR) { $env:PZ_GAME_DIR } else { 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' }),
    [string]$MacDepotDir = '',
    [string]$FmodApiDir = '',
    [string]$JdkZip = '',
    [string]$LwjglNativesDir = '',
    [switch]$CheckOnly,
    [switch]$NoDownload
)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$ProgressPreference = 'SilentlyContinue'

# --- pinos (Project Zomboid 42.21) ---
$PinJarSha  = 'e1a69eb743ede60b213a0fe7f8b83d4fcab773036d256cc4543a336f3b058a33'
$PinJarSize = 65682033
$JdkUrl  = 'https://aka.ms/download-jdk/microsoft-jdk-25-windows-aarch64.zip'
$JdkSha  = 'f227cc459fbd65a42c1b28123d68e207eb500899c1c78238f21a038cd33688a3'   # microsoft-jdk-25.0.5.0.2-windows-aarch64.zip
$LwjglVer = '3.4.1'
$LwjglMods = 'lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd'
$ExpectedDylibs = 17
$FmodVersion = '2.03.09'

$Pending = New-Object System.Collections.ArrayList
function Ok([string]$m)   { Write-Host "[ok]    $m" }
function Info([string]$m) { Write-Host "[info]  $m" }
function Warn([string]$m) { Write-Host "[aviso] $m" -ForegroundColor Yellow }
function Missing([string]$m, [string]$how) { Write-Host "[FALTA] $m" -ForegroundColor Red; if ($how) { Write-Host "        $how" }; [void]$Pending.Add($m) }
function Net([string]$what) {
    if ($CheckOnly -or $NoDownload) { return $false }
    Info "baixando: $what"; return $true
}
function Sha256([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() }

# --- 1. ambiente ---
# Arquitetura fisica da CPU (12 = ARM64). O PowerShell 5.1 do Windows on ARM costuma rodar emulado em x64 e engana outras checagens.
$cpu = $null
try { $cpu = (Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1).Architecture } catch { }
$isArm = ($cpu -eq 12) -or ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') -or ($env:PROCESSOR_ARCHITEW6432 -eq 'ARM64')
if ($isArm) { Ok 'Windows ARM64' } else { Missing "este projeto exige Windows on ARM (ARM64); CPU detectada: codigo $cpu" '' }
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py) { Ok "Python: $((& python --version) 2>&1)" } else { Missing 'Python 3 nao encontrado no PATH' 'Instale o Python 3 (python.org ou Microsoft Store) e reabra o terminal.' }

# --- jogo (Windows) ---
$jar = Join-Path $GameDir 'projectzomboid.jar'
if ((Test-Path -LiteralPath $jar) -and (Test-Path -LiteralPath (Join-Path $GameDir 'ProjectZomboid64.json'))) {
    $sha = Sha256 $jar
    if ($sha -eq $PinJarSha -and (Get-Item -LiteralPath $jar).Length -eq $PinJarSize) { Ok "jogo 42.21 encontrado: $GameDir" }
    else {
        Warn "projectzomboid.jar difere do 42.21 testado (sha256 $sha)."
        Warn 'O port foi feito e testado so com a versao 42.21 do Build 42. Com outra versao as DLLs ponte podem falhar e o launcher recusa o jar (codigo 3) ate o dist ser refeito para ele.'
    }
} else {
    Missing "jogo nao encontrado em $GameDir" 'Instale o Project Zomboid pela Steam (versao Windows) ou passe -GameDir. O jogo e usado so para leitura.'
}

# --- Compatibility Pack (OpenGL via D3D12) ---
$pack = $null
try { $pack = Get-AppxPackage -Name 'Microsoft.D3DMappingLayers*' -ErrorAction Stop } catch { }
if ($pack) { Ok 'OpenCL, OpenGL & Vulkan Compatibility Pack instalado' }
else {
    Warn 'Compatibility Pack nao detectado (nao consegui confirmar; pode estar instalado sob outro nome).'
    Warn 'Instale na Microsoft Store: "OpenCL, OpenGL, and Vulkan Compatibility Pack". Sem ele o OpenGL do Windows on ARM nao sobe o jogo.'
}

# --- 2. JDK 25 ARM64 ---
$JdkDir = Join-Path $Root 'spike-java-arm\jdk-25-arm64'
$JavaExe = Join-Path $JdkDir 'bin\java.exe'
if (-not (Test-Path -LiteralPath $JavaExe)) {
    $zip = $JdkZip
    $dlDir = Join-Path $Root 'spike-java-arm\_downloads'
    if (-not $zip -and (Net 'JDK 25 ARM64 (Microsoft Build of OpenJDK)')) {
        New-Item -ItemType Directory -Force $dlDir | Out-Null
        $zip = Join-Path $dlDir 'jdk.zip'
        Invoke-WebRequest $JdkUrl -OutFile $zip -UseBasicParsing
    }
    if ($zip -and (Test-Path -LiteralPath $zip) -and -not $CheckOnly) {
        $h = Sha256 $zip
        if ($h -ne $JdkSha) { Warn "SHA-256 do JDK ($h) difere do testado ($JdkSha). Continuando; use o JDK 25.0.5 se aparecer erro." }
        $tmp = Join-Path $dlDir 'jdk-extract'
        New-Item -ItemType Directory -Force $dlDir | Out-Null
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -LiteralPath $zip -DestinationPath $tmp
        $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
        New-Item -ItemType Directory -Force (Split-Path $JdkDir) | Out-Null
        Move-Item -LiteralPath $inner.FullName -Destination $JdkDir
        Remove-Item -Recurse -Force $tmp
    }
}
if (Test-Path -LiteralPath $JavaExe) {
    $m = if ($py) { (& python -I (Join-Path $Root 'spike-java-arm\pe_arch.py') (Split-Path $JavaExe) 2>&1) -join ' ' } else { '' }
    if ($m -match 'arm64\s+java\.exe') { Ok "JDK 25 ARM64: $JdkDir" } elseif ($m) { Missing "java.exe nao e ARM64: $m" 'Use o JDK 25 windows-aarch64.' } else { Ok "JDK: $JdkDir (arquitetura nao conferida)" }
} else {
    Missing "JDK 25 ARM64 ausente em $JdkDir" 'Rode setup.ps1 sem -CheckOnly/-NoDownload, ou baixe o zip "Microsoft Build of OpenJDK 25 windows-aarch64" e passe -JdkZip.'
}

# --- 3. toolchain ---
$tc = Join-Path $Root 'tools\toolchain'
$haveTc = (Test-Path (Join-Path $tc 'llvm-mingw')) -and (Test-Path (Join-Path $tc 'cmake')) -and (Test-Path (Join-Path $tc 'ninja\ninja.exe'))
if (-not $haveTc -and (Net 'toolchain (llvm-mingw, CMake, Ninja)')) {
    & (Join-Path $Root 'tools\setup-toolchain.ps1') | Out-Null
    $haveTc = (Test-Path (Join-Path $tc 'llvm-mingw')) -and (Test-Path (Join-Path $tc 'cmake')) -and (Test-Path (Join-Path $tc 'ninja\ninja.exe'))
}
if ($haveTc) { Ok 'toolchain: llvm-mingw, CMake e Ninja ARM64 em tools\toolchain' }
else { Missing 'toolchain ausente em tools\toolchain' 'Rode tools\setup-toolchain.ps1 (precisa de rede).' }

# --- 4. LWJGL natives ARM64 ---
$natives = Join-Path $Root 'spike-java-arm\natives-arm64'
$haveNat = (Get-ChildItem -LiteralPath $natives -Filter *.dll -ErrorAction SilentlyContinue | Measure-Object).Count -ge 6
if (-not $haveNat -and $LwjglNativesDir -and -not $CheckOnly) {
    New-Item -ItemType Directory -Force $natives | Out-Null
    Copy-Item (Join-Path $LwjglNativesDir '*.dll') $natives -Force
    $haveNat = (Get-ChildItem -LiteralPath $natives -Filter *.dll -ErrorAction SilentlyContinue | Measure-Object).Count -ge 6
}
if (-not $haveNat -and (Net "LWJGL $LwjglVer natives windows-arm64 (Maven Central)")) {
    $dl = Join-Path $Root "spike-java-arm\_downloads\lwjgl-$LwjglVer"
    New-Item -ItemType Directory -Force $dl, $natives | Out-Null
    foreach ($mod in $LwjglMods) {
        $name = "$mod-$LwjglVer-natives-windows-arm64.jar"
        $url = "https://repo1.maven.org/maven2/org/lwjgl/$mod/$LwjglVer/$name"
        $f = Join-Path $dl $name
        Invoke-WebRequest $url -OutFile $f -UseBasicParsing
        $want = ((Invoke-WebRequest "$url.sha1" -UseBasicParsing).Content.ToString().Trim() -split '\s+')[0].ToLower()
        $got = (Get-FileHash -LiteralPath $f -Algorithm SHA1).Hash.ToLower()
        if ($got -ne $want) { throw "SHA-1 do $name nao confere com o Maven Central" }
        $x = Join-Path $dl "x-$mod"
        if (Test-Path $x) { Remove-Item -Recurse -Force $x }
        $z = "$f.zip"; Copy-Item -LiteralPath $f $z -Force
        Expand-Archive -LiteralPath $z -DestinationPath $x
        Remove-Item -LiteralPath $z -Force
        Get-ChildItem $x -Recurse -Filter *.dll | ForEach-Object { Copy-Item $_.FullName (Join-Path $natives $_.Name) -Force }
        Remove-Item -Recurse -Force $x
    }
    $haveNat = (Get-ChildItem -LiteralPath $natives -Filter *.dll -ErrorAction SilentlyContinue | Measure-Object).Count -ge 6
}
if ($haveNat) { Ok 'LWJGL natives ARM64 (6 DLLs) em spike-java-arm\natives-arm64' }
else { Missing 'LWJGL natives ARM64 ausentes' 'Rode setup.ps1 com rede (baixa do Maven Central e confere SHA-1).' }

# --- 5. dylibs do depot macOS 108602 ---
$lib = Join-Path $Root 'game-libs\macos-42.21'
function Count-Dylibs([string]$d) { (Get-ChildItem -LiteralPath $d -Filter *.dylib -ErrorAction SilentlyContinue | Measure-Object).Count }
if ((Count-Dylibs $lib) -lt $ExpectedDylibs -and $MacDepotDir -and -not $CheckOnly) {
    if (-not (Test-Path -LiteralPath $MacDepotDir)) { throw "-MacDepotDir nao existe: $MacDepotDir" }
    $src = Get-ChildItem -LiteralPath $MacDepotDir -Recurse -Filter libLighting.dylib -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($src) {
        New-Item -ItemType Directory -Force $lib | Out-Null
        Copy-Item (Join-Path $src.DirectoryName '*.dylib') $lib -Force
    }
}
$nd = Count-Dylibs $lib
if ($nd -ge $ExpectedDylibs) { Ok "dylibs do depot macOS: $nd em game-libs\macos-42.21" }
else {
    Missing "dylibs do depot macOS 108602 ausentes ($nd de $ExpectedDylibs em game-libs\macos-42.21)" `
        'Baixe o depot 108602 do Project Zomboid com a SUA conta Steam (README, passo 3) e rode: .\setup.ps1 -MacDepotDir <pasta do download>.'
}

# --- 6. FMOD ARM64 (conta propria; nao e redistribuido aqui) ---
$fm = Join-Path $Root 'third-party\fmod'
if (-not ((Test-Path (Join-Path $fm 'fmod.dll')) -and (Test-Path (Join-Path $fm 'fmodstudio.dll'))) -and -not $CheckOnly) {
    $api = $FmodApiDir
    if (-not $api) {
        $guess = 'C:\Program Files (x86)\FMOD SoundSystem\FMOD Studio API Windows'
        if (Test-Path $guess) { $api = $guess }
    }
    if ($api) {
        $c = Join-Path $api 'api\core\lib\arm64\fmod.dll'; $s = Join-Path $api 'api\studio\lib\arm64\fmodstudio.dll'
        if ((Test-Path $c) -and (Test-Path $s)) {
            New-Item -ItemType Directory -Force $fm | Out-Null
            Copy-Item $c, $s $fm -Force
        } else { Warn "nao achei api\core\lib\arm64\fmod.dll e api\studio\lib\arm64\fmodstudio.dll em $api" }
    }
}
if ((Test-Path (Join-Path $fm 'fmod.dll')) -and (Test-Path (Join-Path $fm 'fmodstudio.dll'))) {
    if ($py) {
        $o = (& python -I (Join-Path $Root 'tools\check_fmod.py') --core (Join-Path $fm 'fmod.dll') --studio (Join-Path $fm 'fmodstudio.dll') 2>&1) -join ' '
        if ($LASTEXITCODE -eq 0 -or $o -match 'OK') { Ok "FMOD ARM64 em third-party\fmod ($($o -replace '\s+',' '))" }
        else { Missing "FMOD em third-party\fmod nao passou na conferencia: $o" "Precisa da versao $FmodVersion ARM64 (fmod.dll e fmodstudio.dll)." }
    } else { Ok 'FMOD em third-party\fmod (nao conferido: sem Python)' }
} else {
    Missing "fmod.dll / fmodstudio.dll ARM64 ausentes em third-party\fmod" `
        "Baixe o 'FMOD Studio API' para Windows (versao $FmodVersion) em fmod.com/download com a SUA conta FMOD, instale, e rode: .\setup.ps1 -FmodApiDir <pasta 'FMOD Studio API Windows'>."
}

Write-Host ''
if ($Pending.Count -eq 0) { Write-Host 'Tudo pronto. Proximo passo: .\build.ps1' -ForegroundColor Green; exit 0 }
Write-Host "Pendencias ($($Pending.Count)):" -ForegroundColor Red
$Pending | ForEach-Object { Write-Host "  - $_" }
exit 1
