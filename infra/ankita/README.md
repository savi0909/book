# Run PostgreSQL on Ankita — step-by-step

Run these steps on Ankita's Windows laptop. Ankita hosts only the database.
Abhishek's API A/B connect to it over Tailscale, and Flyway creates the schema
(V1–V7) the first time they connect. You do not run any SQL by hand.

Background and design: [docs/ANKITA_POSTGRES.md](../../docs/ANKITA_POSTGRES.md).

| Setting  | Value                                      |
|----------|--------------------------------------------|
| Host     | Ankita's Tailscale IP (now `100.84.247.65`) |
| Port     | `5553` (published on the Tailscale IP only) |
| Database | `booking`                                  |
| User     | `postgres`                                 |
| Password | `postgres`                                 |
| Limits   | 3 GB RAM (4 GB with swap), 2 CPUs          |

## 1. Prerequisites (once)

1. Install **Docker Desktop** and start it. Wait until it shows "Engine running".
2. Install **Tailscale**, sign in to the same tailnet as Abhishek, and confirm
   it is connected:
   ```powershell
   tailscale ip -4          # should print 100.x.y.z (e.g. 100.84.247.65)
   tailscale ping 100.103.238.2   # Abhishek
   ```
3. Install **Git**.

## 2. Get the repository (once)

```powershell
git clone https://github.com/savi0909/book.git sd-book-my-show
Set-Location sd-book-my-show
```

If the repository is already cloned, update it instead:

```powershell
Set-Location sd-book-my-show
git pull
```

## 3. Start PostgreSQL

Open **PowerShell as Administrator**. Elevation is needed once, so the script
can add the firewall rule. Then run:

```powershell
powershell -ExecutionPolicy Bypass -File infra/ankita/start-postgres.ps1
```

Expected last lines:

```
Added firewall rule: sd-book-my-show PostgreSQL (tailnet only)
PostgreSQL is up at 100.84.247.65:5553, database booking, user postgres / password postgres.
On Abhishek: scripts/use-remote-db.ps1 -RemoteHost 100.84.247.65
```

Send the printed IP to Abhishek if it differs from `100.84.247.65`.

If the shell was not elevated, the script prints a `New-NetFirewallRule ...`
command instead. Run that once in an Administrator PowerShell.

## 4. Check that it is running (optional)

```powershell
docker ps --filter name=sd-book-my-show-db
docker compose --env-file infra/ankita/.env -f infra/ankita/compose.postgres.yml exec postgres pg_isready -U postgres -d booking
```

Once Abhishek's APIs have connected, this should list the applied migrations
(1–7):

```powershell
docker compose --env-file infra/ankita/.env -f infra/ankita/compose.postgres.yml exec postgres `
  psql -U postgres -d booking -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank"
```

## 5. On Abhishek (after step 3)

```powershell
Set-Location D:/sd-book-my-show
powershell -ExecutionPolicy Bypass -File scripts/use-remote-db.ps1 -RemoteHost 100.84.247.65
docker compose logs -f api-a api-b
```

On the first connection, Flyway applies V1–V7. The schedule maintainer then
fills today..today+3: about 40k shows and 14M seat rows, which takes several
minutes over Tailscale. To switch back to the local database, run
`scripts/use-local-db.ps1`.

## Daily use

| Task                         | Command (on Ankita, from the repo root)                                       |
|------------------------------|-------------------------------------------------------------------------------|
| Stop (data kept)             | `powershell -ExecutionPolicy Bypass -File infra/ankita/start-postgres.ps1 -Stop` |
| Start again                  | `powershell -ExecutionPolicy Bypass -File infra/ankita/start-postgres.ps1`     |
| View logs                    | `docker compose --env-file infra/ankita/.env -f infra/ankita/compose.postgres.yml logs -f` |

The container uses `restart: unless-stopped`, so it comes back after a reboot
once Docker Desktop starts. If Tailscale's IP changes, run the start script
again. It rewrites `infra/ankita/.env` with the new IP.

The data lives in the Docker volume `sd-book-my-show-db_booking-data`. Stopping
the database or re-running the script keeps it. Do not run `docker compose down -v`
or `docker volume rm` unless you intend to wipe the database.

## Troubleshooting

| Symptom                                           | Fix |
|---------------------------------------------------|-----|
| `Tailscale is not connected on this machine`      | Open Tailscale and sign in, or run `tailscale up`. |
| `Docker Desktop is not running`                   | Start Docker Desktop and wait for "Engine running". |
| `cannot assign requested address` when binding    | Your Docker Desktop version cannot bind a specific IP. In `compose.postgres.yml`, change the port to `"5553:5432"` and rely on the firewall rule. Do not commit that change. |
| Abhishek's `pg_isready` fails, but `tailscale ping` works | Check that the firewall rule exists: `Get-NetFirewallRule -DisplayName 'sd-book-my-show PostgreSQL (tailnet only)'`. If it is missing, re-run step 3 as Administrator. |
| `tailscale ping` says "via DERP"                  | The connection is relayed, which adds 50–200 ms per query. It works, but the APIs will be slower. |
| Laptop sleeps                                     | The APIs lose the database until Ankita wakes up. Nothing is lost. Disable sleep while plugged in during load runs. |

## Security note

The `postgres`/`postgres` credentials were chosen deliberately to keep setup
simple. The only protection is that port 5553 is reachable from the tailnet
alone: it is bound to the Tailscale IP, and the firewall allows only
100.64.0.0/10. Never publish 5553 on `0.0.0.0` or the LAN.
