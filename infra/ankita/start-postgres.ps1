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

if (-not (Test-Path $envFile)) {
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    "POSTGRES_PASSWORD=$password`nTAILSCALE_IP=$ip" | Set-Content -Encoding ascii $envFile
    Write-Host "Created $envFile with a new random password (applies only when the volume is first initialised)."
} else {
    # Keep the password; refresh the IP in case Tailscale re-addressed this machine.
    (Get-Content $envFile) -replace '^TAILSCALE_IP=.*', "TAILSCALE_IP=$ip" | Set-Content -Encoding ascii $envFile
}

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

$password = ((Get-Content $envFile) -match '^POSTGRES_PASSWORD=' -replace '^POSTGRES_PASSWORD=', '')
Write-Host ''
Write-Host 'PostgreSQL is up. Give Abhishek these two values (send the password privately):'
Write-Host "  REMOTE_DB_HOST     = $ip"
Write-Host "  REMOTE_DB_PASSWORD = $password"
