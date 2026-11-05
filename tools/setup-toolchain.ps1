# Instala llvm-mingw (aarch64), CMake (arm64) e Ninja (winarm64) em tools\toolchain\<nome>.
# Fonte: releases oficiais no GitHub; confere o SHA-256 publicado (digest). Nao altera PATH.
# Por padrao usa as versoes de tools\versions.txt (tag e SHA-256 fixos); -Latest pega a ultima release e reescreve versions.txt.
# Cada download vai para uma pasta propria em tools\toolchain\_dl\<nome>. Depois, confira com
# `python -I spike-java-arm\pe_arch.py tools\toolchain\...` que clang.exe/ninja.exe sao arm64.
param([switch]$Latest)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tc = Join-Path $root 'tools\toolchain'
$items = @(
  @{ name='llvm-mingw'; repo='mstorsjo/llvm-mingw'; pat='^llvm-mingw-.*-ucrt-aarch64\.zip$' },
  @{ name='cmake';      repo='Kitware/CMake';       pat='^cmake-.*-windows-arm64\.zip$' },
  @{ name='ninja';      repo='ninja-build/ninja';   pat='^ninja-winarm64\.zip$' }
)
$lines = @()
$verFile = Join-Path $root 'tools\versions.txt'
$pins = @{}
if ((-not $Latest) -and (Test-Path $verFile)) {
  foreach ($l in Get-Content $verFile) { $c = $l -split "`t"; if ($c.Count -ge 4) { $pins[$c[0]] = @{ tag=$c[1]; file=$c[2]; sha=$c[3] } } }
}
foreach ($i in $items) {
  $dest = Join-Path $tc $i.name
  $dl = Join-Path $tc "_dl\$($i.name)"
  New-Item -ItemType Directory -Force $dl | Out-Null
  $pin = $pins[$i.name]
  $relUrl = if ($pin) { "https://api.github.com/repos/$($i.repo)/releases/tags/$($pin.tag)" } else { "https://api.github.com/repos/$($i.repo)/releases/latest" }
  $rel = Invoke-RestMethod $relUrl -Headers @{ 'User-Agent'='setup-toolchain' }
  $a = $rel.assets | Where-Object { $_.name -match $i.pat } | Select-Object -First 1
  if (-not $a) { throw "asset nao encontrado: $($i.repo)" }
  $zip = Join-Path $dl $a.name
  if (-not (Test-Path $zip)) { Invoke-WebRequest $a.browser_download_url -OutFile $zip -UseBasicParsing }
  $h = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
  if ($a.digest) { $exp = ($a.digest -replace '^sha256:','').ToLower(); if ($h -ne $exp) { throw "SHA-256 difere: $($a.name)" } }
  if (-not (Test-Path $dest) -or -not (Get-ChildItem $dest -ErrorAction SilentlyContinue)) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    Expand-Archive $zip $dest
  }
  if ($pin -and $h -ne $pin.sha.ToLower()) { throw "SHA-256 difere do pino em versions.txt: $($a.name)" }
  $lines += "$($i.name)`t$($rel.tag_name)`t$($a.name)`t$h"
}
if ($Latest -or $pins.Count -eq 0) { $lines | Set-Content $verFile }
Get-Content (Join-Path $root 'tools\versions.txt')
