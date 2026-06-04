# Blurt desktop connectivity diagnostic (ASCII-only to avoid encoding issues)
# Usage:  powershell -ExecutionPolicy Bypass -File .\diagnose.ps1

chcp 65001 > $null
$ErrorActionPreference = "SilentlyContinue"
$TsIp = "100.127.0.92"
$Port = 8000

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Blurt Desktop Connectivity Diagnostic" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

Write-Host "`n[1] Process listening on port $Port" -ForegroundColor Yellow
$listens = Get-NetTCPConnection -LocalPort $Port -State Listen
if ($listens) {
    $listens | ForEach-Object {
        $p = Get-Process -Id $_.OwningProcess
        [PSCustomObject]@{
            Addr = $_.LocalAddress
            Port = $_.LocalPort
            PID  = $_.OwningProcess
            Name = $p.ProcessName
            Path = $p.Path
        }
    } | Format-Table -AutoSize
} else {
    Write-Host "  [FAIL] Nothing listening on $Port. main.py not running, or FastAPI bind failed." -ForegroundColor Red
}

Write-Host "`n[2] localhost HTTP ping" -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest "http://localhost:$Port/ping" -TimeoutSec 3 -UseBasicParsing
    Write-Host "  [OK] HTTP $($r.StatusCode)  body=$($r.Content)" -ForegroundColor Green
} catch {
    Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n[3] Tailscale IP HTTP ping ($TsIp)" -ForegroundColor Yellow
try {
    $r = Invoke-WebRequest "http://$TsIp`:$Port/ping" -TimeoutSec 3 -UseBasicParsing
    Write-Host "  [OK] HTTP $($r.StatusCode)  body=$($r.Content)" -ForegroundColor Green
} catch {
    Write-Host "  [FAIL] $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n[4] Inbound BLOCK rules matching 'python'" -ForegroundColor Yellow
$blocks = Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True |
    Where-Object { $_.DisplayName -match "python" }
if ($blocks) {
    Write-Host "  [WARN] Found $($blocks.Count) BLOCK rules. They probably override Allow." -ForegroundColor Red
    $blocks | Select-Object DisplayName, Profile | Format-Table -AutoSize
    Write-Host "  To delete:" -ForegroundColor Cyan
    Write-Host '    Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True | Where-Object { $_.DisplayName -match "python" } | Remove-NetFirewallRule' -ForegroundColor White
} else {
    Write-Host "  [OK] No BLOCK rules for python." -ForegroundColor Green
}

Write-Host "`n[5] Blurt-specific ALLOW rules" -ForegroundColor Yellow
$blurt = Get-NetFirewallRule -DisplayName "*Blurt*"
if ($blurt) {
    $blurt | Select-Object DisplayName, Direction, Action, Enabled | Format-Table -AutoSize
} else {
    Write-Host "  (none; add one with the New-NetFirewallRule command below)" -ForegroundColor Yellow
}

Write-Host "`n[6] Tailscale network status" -ForegroundColor Yellow
$ts = Get-Command tailscale -ErrorAction SilentlyContinue
if ($ts) {
    & tailscale status 2>&1 | Select-Object -First 5
} else {
    Write-Host "  tailscale.exe not in PATH" -ForegroundColor Yellow
}

Write-Host "`n================================================" -ForegroundColor Cyan
Write-Host "  Hints" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "[1] empty  -> Start desktop GUI first (python main.py), then re-run"
Write-Host "[2] OK but [3] FAIL  -> Firewall is blocking. Run (as admin):"
Write-Host '  New-NetFirewallRule -DisplayName "Blurt Desktop 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow -Profile Any' -ForegroundColor White
Write-Host "[4] has BLOCK rules  -> Delete them (cmd shown above), then re-test"
Write-Host "All OK locally but phone still fails -> Check phone is on the same Tailscale tailnet"
