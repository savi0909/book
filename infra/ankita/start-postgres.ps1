# Run on ANKITA from the repository root:  powershell -ExecutionPolicy Bypass -File infra/ankita/start-postgres.ps1
# Starts PostgreSQL bound to this machine's Tailscale IP. The schema is NOT created here:
# Flyway (V1..V7) runs automatically the first time Abhishek's APIs connect.
param([switch]$Stop)
$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path "$PSScriptRoot/../..")
$compose = 'infra/ankita/compose.postgres.yml'
$envFile = 'infra/ankita/.env'

if ($Stop) {
    # Stops the container; the booking-data volume (all data) is kept.
    docker compose --env-file $envFile -f $compose stop
    return
}

$ip = (tailscale ip -4 2>$null | Select-Object -First 1)
if (-not $ip -or $ip.Trim() -notmatch '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$') {
    throw "Tailscale is not connected on this machine (got '$ip'). Run 'tailscale up' first."
}
$ip = $ip.Trim()
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running.' }

# Only the bind address lives in .env; refreshed every start in case Tailscale re-addressed this machine.
"TAILSCALE_IP=$ip" | Set-Content -Encoding ascii $envFile

docker compose --env-file $envFile -f $compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not become healthy; check: docker compose -f infra/ankita/compose.postgres.yml logs' }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$rule = 'sd-book-my-show PostgreSQL (tailnet only)'
if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
    $command = "New-NetFirewallRule -DisplayName '$rule' -Direction Inbound -Protocol TCP -LocalPort 5553 -RemoteAddress 100.64.0.0/10 -Action Allow"
    if ($isAdmin) { Invoke-Expression $command | Out-Null; Write-Host "Added firewall rule: $rule" }
    else { Write-Warning "Not elevated. If Abhishek cannot connect, run once as Administrator:`n  $command" }
}

Write-Host ''
Write-Host "PostgreSQL is up at ${ip}:5553, database booking, user postgres / password postgres."
Write-Host "On Abhishek: scripts/use-remote-db.ps1 -RemoteHost $ip"
