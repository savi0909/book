# Java Book My Show load tester

[Project status — 2026-10-07](../docs/PROJECT_STATUS.md): delivered and locally
verified; generator and business services currently stopped/retained. Final
Ankita execution pending; runtime setup requires a fresh observer and target check.

Standalone Java 21 / Spring Boot 3.5.16 service, adapted from the existing
`D:/java-projects/url-shortener-load-test` Spring Boot generator. Each logical
operation runs on a virtual thread and calls the booking API through blocking
Spring `RestClient`. Its Maven build is independent of the booking backend.

Start with the [tutorial and local/Ankita commands](../docs/JAVA_LOAD_TESTER_TUTORIAL.md).
Read [spec](docs/SYSTEM_SPEC.md), [API](docs/API_REFERENCE.md),
[guide](docs/USER_GUIDE.md), [reference mapping](PARITY.md) and
[verification](docs/VERIFICATION.md).

```powershell
# From D:/sd-book-my-show; no clean command needed.
mvn -B -ntp -f load-tester-java/pom.xml verify
```

Control service: loopback 8135. POST `/load/runs` starts one finite run; GET its
status/report; POST `/load/runs/{id}/stop` stops further arrivals/retries.
HOLD, AVAILABILITY (first 100 seats) and seeded MIXED use a fresh generic event
per run. Default 10 RPS/10s/100 seats/one attempt. Optional bounded IMMEDIATE or
JITTER retries preserve buyer/key/payload. Unknown holds have a separate two-call
discovery budget. HTTP/status metadata is saved; request/response bodies are not.

Local Docker group: `sd-book-my-show-java-load-test`, 384 MiB/1 CPU (was 0.5 until 2026-10-07), separate
from both business services and the retained Node generator. Local runs require
the existing metadata observer. Final remote experiments belong on Ankita.
Redis remains deferred to phase 3. PostgreSQL was 1 GiB when this was written;
it is 3 GB / 2 CPUs since 2026-10-07. The retained loader container still has
0.5 CPU until it is recreated (see [project status](../docs/PROJECT_STATUS.md)).

Import [Postman collection](postman/java-load-tester.postman_collection.json)
and [local environment](postman/local.postman_environment.json) after starting
the loader. This collection deliberately creates only one measured hold; use
the tutorial's explicit 100-call command for the selected 10 RPS smoke.

## Movie advance-booking journeys (2026-10-07)

Study guide: [movie advance-booking tutorial, part 6](../docs/MOVIE_ADVANCE_BOOKING_TUTORIAL.md#part-6--the-movie-journey-workload).
Tests: 19 in total (14 `LoadTesterTest` + 5 `MovieLoadTest`).

`POST /load/movie-runs` starts a finite MOVIE run. Each arrival is one user journey
against the permanent PVR catalog:
1. Browse multiplexes, then the shows for the chosen date.
2. Open a seat map and pick an adjacent 1–10-seat group in one class.
3. Hold the seats. On `SEAT_UNAVAILABLE`, start one second hold with different
   seats under a new key.
4. Checkout (the API returns 202), then poll the booking.
5. Optionally cancel.

Default mix: book 65, cancel 10, abandon 15, browse 10. `hotPercent` 60 sends
users to the blockbusters in 17:00–22:00 shows. The date defaults to tomorrow in
IST and must be within today..today+3.

Local caps: rate ≤ 20/s, ≤ 600 s, ≤ 12,000 journeys, ≤ 16 concurrent HTTP calls,
with a stop at 400 live journeys. Remote caps: 50/s, 1,800 s, 90,000 journeys,
64 calls. Timeouts are recorded as `UNKNOWN_*` and never retried.

Results go to `journeys.json` and `report.json`: outcomes, per-step status and
latency, confirmed seats and revenue, and the hottest shows. The run shares the
observer guard and an exclusive run slot with the generic runner.

```powershell
# Git Bash: prefix with MSYS_NO_PATHCONV=1 so /results/... is not rewritten.
$env:JAVA_LOADER_OBSERVER_STOP_FILE = "/results/java-control-$javaSuffix/STOP"
docker compose -f load-tester-java/compose.local.yml up -d --force-recreate
Invoke-RestMethod -Method Post http://127.0.0.1:8135/load/movie-runs `
  -ContentType application/json -Body '{"rate":5,"seconds":20}'
```
