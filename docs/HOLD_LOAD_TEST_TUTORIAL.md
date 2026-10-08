# Build here, run the hold comparison on Ankita

Delivered 2026-10-06 after the user selected a checkoutable load-test module.
This implements the **generic hold client comparison** portion of the
[handover](HANDOVER_HOLD_RETRY_BUILD.md). Abhishek hosts services; the learner
will run business load on Ankita. Remote measurements remain pending.
Server shared admission, movie load/payment workflows, live availability and
100K visitors are separate future deliveries.

> **Current state (2026-10-08):** movie journey load now exists in the *Java*
> loader (`POST /load/movie-runs`, 2026-10-07). See
> [advance-booking tutorial, part 6](MOVIE_ADVANCE_BOOKING_TUTORIAL.md#part-6--the-movie-journey-workload).
> This Node module remains the generic retry-comparison study. Shared admission,
> live availability and 100K visitors are still future work.

The user subsequently authorized bounded local loader validation. Two small
actual-stack checks passed; see [evidence](HOLD_LOAD_TEST_VERIFICATION.md).
The final business comparison remains an Ankita run.

## What we are testing

A logical hold is one buyer/key/input tuple. An HTTP attempt is one invocation
of the transport for that tuple; a connection failure may mean no bytes reached
the server. The report's transmission count is this client attempt count, not
proof that the backend received every attempt.

| Arm | First arrivals | Later attempts | Identity |
| --- | --- | --- | --- |
| Immediate | Same index × configured interval | No client delay beyond a server delay floor | Original buyer/key/input |
| Jitter | Same index × configured interval | Seeded full jitter, exponential windows capped at 2s; server floor wins | Original buyer/key/input |

For each arm there is a different retained event and a different buyer/key
namespace. Both events have equivalent fresh inventory. With the small config,
20 buyers compete over 10 seats. Conflict counts can vary with timing; this is
not a deterministic promise about winners or jitter improving every sample.

The four-attempt bound includes the first attempt. A ten-second deadline begins
at each scheduled arrival, including time lost to a slow generator. Every request
has a 3.5s whole-response timeout, capped by the remaining operation/run deadline.
There is no hidden local queue: attempting to exceed eight active logical operations stops
the arm and marks the comparison invalid. Later operations become NOT_STARTED.

There is only one outstanding **client** request per logical hold. A timeout
does not cancel server work, so the backend may still be processing a previous
attempt. Durable keys/seat locks preserve correctness. The client cap does not
bound all backend work or protect the connection pool before waiting.

## Follow one hold through the real code

Read these files in this order, using one operation before reading the scheduler:

1. [BookingController.hold](../src/main/java/com/example/booking/BookingController.java): 201 fresh or 200 replay.
2. [BookingService.hold](../src/main/java/com/example/booking/BookingService.java): scoped key advisory lock, durable replay before seat selection, seat lock, logical expiry, insert and commit.
3. [BookingStore.tx](../src/main/java/com/example/booking/BookingStore.java): 2s lock/3s statement/5s transaction settings. None is a universal end-to-end HTTP deadline.
4. [Errors](../src/main/java/com/example/booking/Errors.java): DB failures become 503 with `Retry-After: 1`; that response may be ambiguous.
5. [HAProxy](../infra/haproxy.cfg): `retries 0` and `retry-on none`; the client owns this retry loop.
6. [lib.mjs](../load-test/lib.mjs): stable identities, seeded delay, deadline/budget checks, bounded HTTP body read, response validation and summaries.
7. [runner.mjs](../load-test/runner.mjs): open arrival schedule, bounded active logical operations, resource/deadline stops.
8. [cli.mjs](../load-test/cli.mjs): fixtures, host guard, write-before-send journals, exclusive markers, discovery and retained output.
9. [ingress.mjs](../load-test/ingress.mjs): default-off busy/committed-loss fixtures outside the backend transaction.
10. [PostgreSQL client test](../load-test/test/postgres-contract.mjs) and its [Maven entry](../src/test/java/com/example/booking/HoldLoadClientIntegrationTest.java): actual client, real DB, same booking/expiry after replay.

The transport uses Node HTTP(S) with one fresh connection per invocation and
no redirect following, connection reuse or automatic retry. This is an explicit
lab transport choice; its connect cost is part of Ankita's observed latency.

## Classify responses before retrying

| Observation | Action |
| --- | --- |
| Valid 201/200 with matching buyer, event and seat | RESOLVED; preserve booking ID, creation time and expiry |
| 409 SEAT_UNAVAILABLE | Terminal business conflict; one attempt |
| 400, other 409, 403, 404 or redirect | Stop; no new key or payload |
| 408/429/500/502/503/504 or transport/body failure | Bounded retry with the same identity |
| Controlled LAB_BUSY/INGRESS_BUSY | Known rejection before this ingress forwards a hold |
| DATABASE_UNAVAILABLE, lost/malformed success or transport loss | May have committed; keep uncertainty until valid discovery |
| Budget/deadline exhausted after ambiguity | UNRESOLVED, never invented payment/booking failure |

Both modes honor integer-seconds and HTTP-date `Retry-After`. A floor exceeding
the remaining deadline stops attempts. Floors can make immediate and jittered
delays identical; this is correct. The capped window is a client jitter cap, not
permission to shorten a longer server floor.

## Prepare on Abhishek

Prerequisites: the existing normal base+failover stack, Node >=22, Docker CLI,
and an available private tailnet path. Delivery used a temporary loopback ingress
for user-authorized local checks. Compose/tailnet exposure were unchanged; nothing
was installed or run on Ankita.

Observed 2026-10-06: Abhishek `100.103.238.2`, Ankita `100.84.247.65` (offline).
Recheck `tailscale status` before using the committed sample address. Use a literal
current Tailscale IPv4; the CLI rejects a public/wildcard ingress bind. Port 8134 is
the proposed dedicated fixture ingress, not an already-running listener.

```powershell
Set-Location D:/sd-book-my-show
node --test load-test/test/*.test.mjs
node load-test/cli.mjs prepare --config load-test/config.small.json --out load-test/results/fixtures-01
node load-test/cli.mjs plan --fixtures load-test/results/fixtures-01/fixtures.json
```

Prepare sends exactly two non-idempotent fixture-creation calls to loopback API A.
It does not run holds, payments or a workload. A lost create response aborts;
inspect retained events rather than automatically retrying creation. The partial
manifest is retained. Only a complete manifest can start a run.

Transfer `fixtures-01/fixtures.json` to Ankita using your chosen file-transfer
method. Generated fixtures, reports and local markers are intentionally ignored
by Git. Checking out code does not transfer runtime fixture IDs. Keep a copy of
the manifest on Abhishek to scope the ingress.

Create a token locally and give the same value to Ankita through your private
channel. Keep it in each process environment; do not commit it or save it in
reports. In a PowerShell terminal on Abhishek:

```powershell
$env:HOLD_LAB_TOKEN = node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))"
```

Set this same environment value in the terminal that will start the ingress.
The token is a lab ingress guard, not production buyer authentication.

Start the observer in a separate Abhishek terminal, then wait for `observerReady`:

```powershell
node load-test/observe.mjs load-test/results/observer-01 300 load-test/results/control-01/STOP
```

It samples only this Compose project's four containers and aggregate
`pg_stat_activity` counts. It sends no business HTTP requests, captures no SQL
query text and adds one short read-only SQL observation per sample. Docker stats
can take longer than one second; timestamps disclose the actual cadence.

Then start the scoped ingress in the terminal containing the token:

```powershell
node load-test/cli.mjs ingress --fixtures load-test/results/fixtures-01/fixtures.json --bind 100.103.238.2 --port 8134 --lifetime-seconds 300 --stop-file load-test/results/control-01/STOP --enable-faults
```

The ingress forwards only exact POST `/api/holds` payloads/keys from the manifest
to loopback gateway 8132. All other routes, arbitrary fixtures and unauthenticated
calls are rejected. Database, replica management and `/api/demo` routes remain
outside this business ingress. There is no ingress HTTP control API.

Remove `--enable-faults` for a no-fault baseline, with fresh fixtures and output
paths. Fault-enabled runs deterministically reject the first attempt of every
third operation with LAB_BUSY/Retry-After1. Every seventh operation's first
successful upstream 201 is consumed and its downstream socket destroyed. Index 0
therefore exercises both busy and committed-loss handling when it wins the seat.
Repeated keys share the ingress's attempt/loss state; counters are bounded by the
fixture operation set and reset only when that scoped process restarts.

The ingress receives the complete upstream response after the DB transaction.
It never delays while holding a booking connection/lock. Its 32-connection and
32-active-handler caps are process limits on this study ingress; they are not
shared A/B admission. Ingress restarts reset fault counters and its request cap,
so do not restart it midway through a comparison.

## Run on Ankita

Check out the committed repository, install/select Node >=22 if needed and put
the transferred manifest at the same example relative location. The client itself
needs neither Java, Maven, Docker nor npm dependencies. The repository contains
Java tests for Abhishek's build validation, not prerequisites for Ankita's run.

Set `HOLD_LAB_TOKEN` to the privately supplied value in Ankita's terminal. Recheck
the machine name with `hostname`; the CLI requires the configured generator host
`ankita` and refuses to start on `abhishek`. If Ankita's actual hostname differs,
update `generatorHost` **before** preparing the next fixture manifest.

```powershell
Set-Location D:/sd-book-my-show
node load-test/cli.mjs plan --fixtures load-test/results/fixtures-01/fixtures.json
tailscale status
tailscale ping 100.103.238.2
node load-test/cli.mjs run --fixtures load-test/results/fixtures-01/fixtures.json --out load-test/results/run-01
```

Save the ping's direct/DERP result alongside the run. A path can change; record
before and after, rather than inferring authenticated peer identity from HTTP
latency. Tailnet ACLs/firewall/reachability still need verification on the real
machines. Do not publish the original application listener to make this work.

The small run has 20 logical holds/arm, 80 policy attempts maximum/arm (160 total),
an initial 10 logical arrivals/s, and a 30s hard run ceiling/arm. Both arms run
sequentially with 2s cooldown; by default immediate runs first. Only the first
arrival schedule is fixed; retries are deliberately the variable being compared.

The run stops on attempting to exceed eight concurrent logical operations, 20 consecutive transient
responses, process CPU >90% of one core, RSS >256MiB, event-loop lag >250ms,
host CPU >90%, host memory >90%, an interrupt or the run deadline. Sampling is
periodic, so these are automatic study stop thresholds, not instantaneous OS
resource guarantees. Concurrency saturation aborts instead of reducing offered
traffic silently. A stopped first arm prevents the second arm from starting.

The observer closes the ingress after three bad pressure samples: any container
CPU >90%, container memory >85%, booking DB connections >20, DB lock waits >4,
host CPU/memory >90%, or unavailable observation. Normal observer completion
also writes STOP. Missing/stale (>15s) heartbeat closes ingress; its own finite
lifetime is a final bound. In-flight upstream work may finish after closure.

Exit 0 means both arms finished without a stop condition, not that every hold
succeeded or uncertainty disappeared. Exit 2 means saturation/stops, or unresolved
discovery; exit 1 means configuration/operational error. Keep all artifacts.

## Discover uncertain keys after quiescence

Wait for the original runner to exit and for bounded in-flight work to finish.
Keep the same scoped ingress and observer alive during the separate phase:

```powershell
node load-test/cli.mjs discover --fixtures load-test/results/fixtures-01/fixtures.json --run load-test/results/run-01 --out load-test/results/discovery-01
```

Only unresolved operations and write-before-send journal entries without a
completed outcome are selected. NOT_STARTED or definite conflicts are not sent.
Each candidate gets at most two additional attempts in this explicitly separate
phase, with the same canonical identity and jitter/deadline/resource rules.
The absolute discovery cap is 80 attempts for both full arms. Report recovery
transmissions separately; never omit them to make amplification look smaller.

A replay 200 discovers an existing booking. A discovery 201 means this recovery
call created the booking; it does not prove the original timed-out call committed.
A terminal response after previous uncertainty stays UNRESOLVED. A hard crash
after journaling but before transmission is also conservatively uncertain.

Exclusive `.started` and `.discovery.started` markers prevent another local
policy run or discovery admission against the same manifest. There is no global
marker across copied checkouts; use exactly one agreed Ankita run directory and
do not copy a manifest to multiple runners. A discovery crash consumes its local
phase admission; inspect retained state rather than resetting markers/budgets.
Checkpoint JSONL is usable when the full report is missing/truncated; incomplete
final checkpoint lines are ignored. Journal writes are fsynced before transport.

## Bounded local functionality check

The user explicitly permitted validating the loader on Abhishek. Copy the small
config to a new file, set targetBaseUrl to `http://127.0.0.1:8134`, prepare new
fixtures, start observer and loopback ingress, then use:

```powershell
node load-test/cli.mjs run --fixtures YOUR_NEW_LOCAL_FIXTURES_JSON --out YOUR_NEW_LOCAL_RUN_DIR --local-validation
node load-test/cli.mjs discover --fixtures YOUR_NEW_LOCAL_FIXTURES_JSON --run YOUR_NEW_LOCAL_RUN_DIR --out YOUR_NEW_LOCAL_DISCOVERY_DIR --local-validation
```

Local mode rejects non-loopback targets, >20 operations/arm, >8 concurrent or
>30s/arm. Output is labelled local-bounded-validation. Normal final/sustained
runs still require Ankita, tailnet IPv4 and the ingress token.

Delivered local pair:20 bookings from 40 offered operations/56 attempts. Second
tiny check:2 operations/arm,maxAttempts 1,busyEvery 0,lossEvery 1; four lost commits
remained unresolved until four replay 200 discovery calls. Repeating either
admitted phase with existing markers was rejected before additional HTTP.

## Read the reports and audit

`comparison.json` contains validity, configuration identity and summaries.
Each arm's JSON includes all logical outcomes, selected IDs/expiry, attempts,
per-second POST/status/code rates, average/p50/p95/p99 and resource samples.
No request/response body or token is saved. The manifest stores chosen fixture
IDs/config; operations can be reconstructed from their index and arm.

Read logical operations, started operations, transmissions, attempts/operation,
replays, unresolved count and amplification together. The denominator is offered
logical operations; NOT_STARTED is explicit. Successful-write percentiles use
only validated observed 201s. Lost 201 commits discovered through 200 are in replay
and logical latency, not the observed 201 distribution. All-response percentiles
include fast 409s. Per-second status buckets use transmission-start time and may
contain gaps with no requests. Empty sample distributions return null percentiles.

Ankita's timings are end-to-end from that generator. The observer provides
container/host CPU/memory and DB connections/lock-wait counts. Hikari pool-wait
metrics and backend/gateway percentiles are not exposed/collected by this module;
do not infer them from end-to-end samples. Sampling can miss short lock spikes.

After business work quiesces, run the aggregate audit on Abhishek:

```powershell
$holdFixture = Get-Content load-test/results/fixtures-01/fixtures.json -Raw | ConvertFrom-Json
if ($holdFixture.runId -notmatch '^[a-f0-9]{16}$') { throw 'Invalid run ID' }
Get-Content load-test/audit.sql -Raw | docker compose -p sd-book-my-show -f compose.yml -f compose.failover.yml exec -T postgres psql -U booking_demo -d booking -v ON_ERROR_STOP=1 -v "run_id=$($holdFixture.runId)"
```

The [SQL audit](../load-test/audit.sql) uses one repeatable-read, read-only snapshot.
All violation counts should be zero: valid duplicate generic owners, duplicate
keys, changed generic TTL (>100ms tolerance), multiple/missing HELD audit events,
valid movie member ownership mismatches and invalid confirmed movie group size.
Run booking counts supply context; an empty run alone proves no workload result.
Compare retained booking IDs/expiry with client reports as well. Historical movie
checks remain independent; this generic workload does not stress movie flows.

Stop the ingress/observer with Ctrl+C or let their limits expire. No compose
overlay or scheduler flag changes are required. Existing normal workers and
retained fixture/database data remain. No cleanup/delete/prune/clean command is
part of this delivery.

## Repeat and learn

Prepare fresh fixtures for each repeat. Run at least three matched pairs with
the same configuration/seed, alternating `--first-arm jitter` and the default
order to reduce order bias. Compare validity before percentiles. If saturation
occurs, inspect the stop reason, select a smaller fixed arrival rate for **both**
arms and prepare a new manifest. Do not change bounds halfway through a fixture.

The defaults are a finite correctness-oriented first run, not a capacity/SLO
claim. Hard upper validation bounds are 200 operations/arm, four policy attempts,
two discovery attempts, 32 concurrent logical operations and 120s/arm. Increasing
within these bounds is a subsequent measured user run; 100K is unsupported.

To study admission later, keep this client baseline and workload shape, and
compare jittered runs with a separately implemented server admission mode. A
rate cap controls admissions/time; a concurrency cap controls work in flight.
A/B need a genuinely atomic shared coordinator plus separately bounded discovery.
None of those mechanisms is implemented by this client or its fault ingress.

Five interview points:

1. Idempotency protects identity, while budgets/deadlines/jitter protect capacity.
2. Timeout is uncertainty; preserve the key and distinguish replay 200 from recovery 201.
3. Seat 409 is business contention; backoff cannot create inventory.
4. Fixed arrivals and separate fixtures make a client-policy comparison reviewable; floors, saturation and host order can dominate a tiny sample.
5. Process caps, shared admission and inventory locking solve different problems; measure the coordinator's cost/failure domain before claiming fleet protection.

Exercise: trace operation 0 through LAB_BUSY, a lost committed 201 and replay 200.
Predict the booking count, attempt count and expiry. Then set a Retry-After floor
larger than the deadline in the fake policy test: predict why no second request
is sent and why the DB-failure version stays unresolved.

## Checkout without an origin

> **Current state (2026-10-08):** origin now exists. On Ankita, run
> `git clone https://github.com/savi0909/book.git sd-book-my-show` instead of using
> a bundle. The section below is kept as the 2026-10-06 procedure.

This repository still has no origin. The delivery includes a verified Git bundle
under `target/` for manual transfer; the final chat gives its exact filename.
On Ankita, after transferring it, create a **new** checkout:

```powershell
git clone D:/TRANSFERRED_PATH/sd-book-my-show-load-test-COMMIT.bundle D:/sd-book-my-show
```

Replace the bundle path/COMMIT with the actual delivered file and select a new
destination if that directory already exists. Do not overwrite another checkout.
If you later configure the intended standalone origin and push, a normal clone
works instead. No original Java workspace remote is reused.
