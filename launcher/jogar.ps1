<#
Perfil de inicializacao do usuario: abre o Project Zomboid (ARM64) com as opcoes escolhidas abaixo.
Argumentos extras sao repassados ao run-game.ps1 (ex.: .\jogar.ps1 -DryRun) e podem sobrescrever o perfil (ex.: .\jogar.ps1 -FrameRate 60). Sem timeout.
Se o jogo sair com erro (ou o launcher falhar), a janela mostra a mensagem, o caminho do ultimo logs\game-*.log e espera Enter
(util quando aberto pelo atalho do Menu Iniciar). Para criar o atalho no Menu Iniciar: .\criar-atalho.ps1
#>
param(
    # ===================== CONFIGURACAO (edite os valores padrao aqui) =====================
    # Resolucao de desenho (LARGURAxALTURA). Menos pixels = mais FPS; o Windows estica a janela. Grava so em cache\options.ini.
    [string]$Resolution = '1152x768',
    # Limite de FPS do jogo (10 a 240). Grava so em cache\options.ini.
    [int]$FrameRate = 30,
    # Contornos de sincronizacao CPU-GPU do Mesa GLon12 (lista separada por virgula; ver run-game.ps1 -GlSync; 'none' desliga).
    [string]$GlSync = 'swapfence,uploadflush,otherflush',
    # Contorno para buffers mapeados sem sincronizar (glMapBufferRange UNSYNCHRONIZED): none, sync ou invalidate (ver run-game.ps1 -VboSync).
    [string]$VboSync = 'sync'
    # =======================================================================================
)
$codigo = 0
try {
    $global:LASTEXITCODE = 0
    & "$PSScriptRoot\run-game.ps1" -Resolution $Resolution -FrameRate $FrameRate -GlSync $GlSync -VboSync $VboSync @args
    $codigo = if ($null -eq $LASTEXITCODE) { 0 } else { [int]$LASTEXITCODE }
} catch {
    Write-Host ""
    Write-Host "ERRO ao iniciar: $($_.Exception.Message)" -ForegroundColor Red
    $codigo = 1
}
if ($codigo -ne 0) {
    Write-Host ""
    Write-Host "O run-game.ps1 terminou com codigo $codigo. Veja a mensagem acima." -ForegroundColor Yellow
    $ultimo = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '..\logs') -Filter 'game-*.log' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($ultimo) { Write-Host "Ultimo log: $($ultimo.FullName)" -ForegroundColor Yellow }
    Read-Host "Pressione Enter para fechar"
}
exit $codigo
