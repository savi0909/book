# Run on ABHISHEK from the repository root to switch API A/B back to the local PostgreSQL.
# Recreates the APIs from the base + failover files only; the local volume was never modified.
$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path "$PSScriptRoot/..")
docker compose -f compose.yml -f compose.failover.yml up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Local stack did not become healthy; check: docker compose logs' }
Write-Host 'APIs use the local PostgreSQL (127.0.0.1:5553) again.'
