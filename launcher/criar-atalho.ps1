<#
Cria (ou recria, de forma idempotente) o atalho "Project Zomboid ARM64" no Menu Iniciar do usuario, apontando para launcher\jogar.ps1 deste repositorio.
Uso: .\criar-atalho.ps1 [-Nome <texto>] [-IconGameDir <p>]
O atalho sempre chama o launcher\jogar.ps1 deste repositorio (pasta do jogo, resolucao etc. se configuram em jogar.ps1/run-game.ps1, nao aqui).
-IconGameDir so escolhe de onde vem o icone (ProjectZomboid64.exe da pasta do jogo, se existir; padrao: $env:PZ_GAME_DIR ou a pasta da Steam). O atalho abre o PowerShell minimizado. Nada e escrito na pasta do jogo.
#>
param(
    [string]$Nome = 'Project Zomboid ARM64',
    [string]$IconGameDir = $(if ($env:PZ_GAME_DIR) { $env:PZ_GAME_DIR } else { 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' })
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Jogar = Join-Path $Root 'launcher\jogar.ps1'
if (-not (Test-Path -LiteralPath $Jogar)) { [Console]::Error.WriteLine("ERRO: jogar.ps1 nao encontrado: $Jogar"); exit 2 }
$Pasta = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
New-Item -ItemType Directory -Force -Path $Pasta | Out-Null
$Lnk = Join-Path $Pasta "$Nome.lnk"
$sh = New-Object -ComObject WScript.Shell
$s = $sh.CreateShortcut($Lnk)
$s.TargetPath = 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'
$s.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Jogar`""
$s.WorkingDirectory = $Root
$s.WindowStyle = 7
$s.Description = 'Project Zomboid nativo ARM64 (zomboid-arm64)'
$Exe = Join-Path $IconGameDir 'ProjectZomboid64.exe'
if (Test-Path -LiteralPath $Exe) { $s.IconLocation = "$Exe,0" }
$s.Save()
$r = $sh.CreateShortcut($Lnk)
"Atalho: $Lnk`nTargetPath: $($r.TargetPath)`nArguments: $($r.Arguments)`nWorkingDirectory: $($r.WorkingDirectory)`nIconLocation: $($r.IconLocation)"
