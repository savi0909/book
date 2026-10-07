# PostgreSQL on Ankita over Tailscale

Written 2026-10-07. The scripts were **not executed**: Ankita was offline at the
time, and the user asked for no further test runs. Every script checks its own
prerequisites before it changes anything.

## Topology

```
Abhishek (100.103.238.2)                         Ankita (100.84.247.65)
gateway :8132 -> api-a / api-b  ── Tailscale ──>  postgres :5553 (Tailscale IP only)
local postgres: stopped, volume kept              volume sd-book-my-show-db_booking-data
```

The schema is migrated by **Flyway, not by a separate script**. The first time
the APIs start against Ankita's empty database, they:

1. Apply V1–V7. This includes the identical PVR catalog: 303 sites and 1,971
   screens, with the same deterministic IDs.
2. Run the schedule maintainer, which fills today..today+3 (about 40k shows and
   14M seat rows).

Bookings and the earlier experiment fixtures stay in Abhishek's local volume.
That volume is stopped, not deleted.

## On Ankita

Prerequisites: Docker Desktop is running, Tailscale is connected, and the
repository is checked out.

```powershell
git clone https://github.com/savi0909/book.git sd-book-my-show
Set-Location sd-book-my-show
git checkout feature/pvr-rolling-catalog    # until PR #1 is merged
powershell -ExecutionPolicy Bypass -File infra/ankita/start-postgres.ps1
```

The script:
- Verifies the Tailscale IP (must be in 100.64.0.0/10) and that Docker is running.
- Writes `infra/ankita/.env` (git-ignored) with a random 48-hex-character
  password. The password only applies when the volume is first initialised.
- Starts postgres:16 (3 GB, 2 CPUs) with the port published only on
  `<tailscale-ip>:5553`.
- Adds a Windows Firewall rule allowing TCP 5553 from 100.64.0.0/10 only. This
  needs an elevated shell; otherwise it prints the command to run.
- Prints `REMOTE_DB_HOST` and `REMOTE_DB_PASSWORD`. Send the password privately.

To stop the database (data is kept), run the same script with `-Stop`.

## On Abhishek

```powershell
Set-Location D:/sd-book-my-show
$env:REMOTE_DB_PASSWORD = '<password from Ankita>'
powershell -ExecutionPolicy Bypass -File scripts/use-remote-db.ps1 -RemoteHost 100.84.247.65
docker compose logs -f api-a api-b     # Flyway lines, then "Movie schedule: ... added N shows"
```

`use-remote-db.ps1` steps:
1. Runs `tailscale ping`.
2. Runs `pg_isready` from **inside a container**, which is the same network path
   the APIs use.
3. Recreates API A/B with `compose.remote-db.yml`. Connect timeout is 5 s and
   socket timeout 10 s, to absorb tailnet latency.
4. Stops the local postgres (pass `-KeepLocalPostgres` to skip this).

Switch back with `scripts/use-local-db.ps1`.

## Things to expect

- **Latency.** Every SQL statement now crosses Wi-Fi and Tailscale. A direct
  connection adds a few ms per round trip; a DERP relay adds 50–200 ms. Run
  `tailscale ping 100.84.247.65`: "via DERP" means relayed. In-transaction
  `statement_timeout` (3 s) and `lock_timeout` (2 s) are unchanged, so expect
  higher API latency rather than new failures. Very slow links can still time
  out maintainer batches; they retry on the next tick.
- **Availability.** If Ankita sleeps or disconnects, API readiness fails (it
  includes the database). Holds and payments stop until Ankita reconnects. Nothing
  is lost: PostgreSQL stays authoritative, and the APIs reconnect by themselves.
- **Security.** The port is reachable only from the tailnet, and the firewall rule
  also limits it to 100.64.0.0/10. The password is random and uses SCRAM. Do not
  publish 5553 on `0.0.0.0` or the LAN.
- **Ankita's memory.** PostgreSQL may use up to 3 GB (4 GB with swap) and 2 CPUs on
  Ankita.

## If Docker cannot bind the Tailscale IP

Some Docker Desktop versions refuse to bind a specific host IP (`cannot assign
requested address`). In that case:

1. In `compose.postgres.yml`, change the port to `"5553:5432"`.
2. Rely on the firewall rule (TCP 5553 from 100.64.0.0/10 only).
3. Confirm that the LAN cannot reach it: `Test-NetConnection <lan-ip> -Port 5553`
   from another LAN device should fail.
