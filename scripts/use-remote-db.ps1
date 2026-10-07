# Run on ABHISHEK from the repository root after Ankita ran infra/ankita/start-postgres.ps1.
#   $env:REMOTE_DB_PASSWORD = '<from Ankita>'
#   powershell -ExecutionPolicy Bypass -File scripts/use-remote-db.ps1 -RemoteHost 100.84.247.65
# Restarts API A/B against Ankita's PostgreSQL; on an empty database Flyway creates V1..V7
# and the schedule maintainer then fills today..today+3 (a few minutes, logged by the APIs).
param(
    [string]$RemoteHost = '100.84.247.65',
    [switch]$KeepLocalPostgres
)
$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path "$PSScriptRoot/..")
if (-not $env:REMOTE_DB_PASSWORD) { throw 'Set $env:REMOTE_DB_PASSWORD to the password printed on Ankita.' }
$env:REMOTE_DB_HOST = $RemoteHost

tailscale ping --c 3 $RemoteHost
if ($LASTEXITCODE -ne 0) { throw "Ankita ($RemoteHost) is not reachable over Tailscale." }
# Probe from inside Docker: the APIs connect from containers, not from the Windows host.
docker run --rm postgres:16-alpine pg_isready -h $RemoteHost -p 5553 -d booking -U booking_demo -t 5
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL on ${RemoteHost}:5553 is not accepting connections from Docker." }

$files = @('-f', 'compose.yml', '-f', 'compose.failover.yml', '-f', 'compose.remote-db.yml')
docker compose @files up -d --wait api-a api-b gateway
if ($LASTEXITCODE -ne 0) { throw 'APIs did not become healthy; check: docker compose logs api-a api-b' }
if (-not $KeepLocalPostgres) {
    # Stop (not remove) the local database so it is not mistaken for the active one; data is kept.
    docker compose stop postgres
}
Write-Host "APIs now use PostgreSQL at ${RemoteHost}:5553. Gateway: http://127.0.0.1:8132"
Write-Host 'First start on an empty database: watch "docker compose logs -f api-a api-b" for the Movie schedule fill line.'
