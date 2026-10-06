# Local 10 requests/second in a separate Docker group

User-selected2026-10-06: run the small test on this machine, isolate the loader in
its own Docker group, increase PostgreSQL to1 GiB, and defer Redis to phase3.

The original [client/tutorial](HOLD_LOAD_TEST_TUTORIAL.md) remains the retry study.
This delivery adds a fixed **single-arm smoke**:100 fresh generic holds, one every
100ms, maxAttempts1, no faults,100 distinct seats. Two fixture-creation calls are
setup outside the100-request measurement. The second retained fixture is unused;
it preserves the existing manifest format. No payments/movie workload are sent.

## Docker groups and resources

| Project | Service | Current limit |
| --- | --- | --- |
| sd-book-my-show | postgres |1 GiB memory;2 GiB combined memory+swap ceiling |
| sd-book-my-show | api-a / api-b / gateway | Existing limits/configuration retained |
| sd-book-my-show-load-test | load-tester |256 MiB,0.5 CPU,64 PIDs,128 MiB Node heap |

[Load Compose](../load-test/compose.yml) uses an external `sd-book-my-show_default`
network to reach internal API A and the gateway. It owns no database, broker or
server containers, and publishes no port. Docker Desktop displays the load-test
project separately; this is process/resource isolation, not a separate host or
failure domain. The completed loader and unit-test container are retained, exited.

[Dockerfile](../load-test/Dockerfile) uses a digest-pinned locally available Node24
image. Node inside the observed container was24.21.0. Generated outputs are excluded
from image context and mounted separately under `/results`.

PostgreSQL was changed persistently in [base Compose](../compose.yml) and updated
live with `docker update --memory 1g --memory-swap 2g sd-book-my-show-postgres-1`.
The actual container ID and `sd-book-my-show_booking-data` volume remained the same;
no database restart/replacement/delete was needed. Verified memory1073741824 bytes,
healthy. The swap setting is a total ceiling, not2 GiB extra RAM. shared_buffers
and other SQL settings are unchanged; memory headroom does not establish capacity.

## Repeat with new output/control names

Keep the existing base+failover stack running. From the repository root, build:

```powershell
$env:HOLD_SMOKE_RUN_NAME = 'docker-smoke-NEW_UNIQUE_NAME'
$env:HOLD_SMOKE_CONTROL_FILE = '/results/docker-control-NEW_UNIQUE_NAME/STOP'
docker compose -f load-test/compose.yml config --quiet
docker compose -f load-test/compose.yml build load-tester
```

In another terminal start a finite observer and wait for `observerReady`:

```powershell
node load-test/observe.mjs load-test/results/docker-observer-NEW_UNIQUE_NAME 60 load-test/results/docker-control-NEW_UNIQUE_NAME/STOP
```

Then run in the terminal with the environment variables:

```powershell
docker compose -f load-test/compose.yml up --abort-on-container-exit --exit-code-from load-tester load-tester
```

The observer uses only metadata/aggregate SQL for the service project. The loader
requires a <15s heartbeat and stops new sends when STOP appears or observation
becomes stale. Existing resource/concurrency/error/deadline checks remain active.
At most100 hold calls are admitted; retries cannot increase the rate. If it stops,
NOT_STARTED/UNRESOLVED outcomes remain explicit rather than replacing them with
success. The script returns2 for incomplete/unexpected outcomes and1 for setup
errors. No auto-retry of ambiguous fixture creation is performed.

The [entrypoint](../load-test/docker-smoke.mjs) refuses another hostname or an
existing output directory. It calls the same runArm/execute/transport as the normal
module. Each sent identity is journaled before transport; checkpoints/report omit
payload and response bodies. Local Docker execution is explicitly labelled and
does not use the normal Ankita CLI host override. The original <=20-operation
--local-validation guard remains intact; this separately selected fixed smoke
supports100 requests. Long/final remote experiments still belong on Ankita.

Reports are in `load-test/results/YOUR_RUN_NAME`. The final hold starts at9.9s;
the nominal offered window is10s, and elapsed time includes its response. Request
buckets use transmission start; percentile fields measure client HTTP round trips.
Logical latency additionally includes scheduled-arrival/journal time. Node's
container-host metrics describe the Docker VM; observer host metrics describe the
Windows machine. Do not combine them as independent physical hosts.

After the client exits, audit the retained run ID:

```powershell
$holdDockerFixture = Get-Content "load-test/results/$env:HOLD_SMOKE_RUN_NAME/fixtures.json" -Raw | ConvertFrom-Json
Get-Content load-test/audit.sql -Raw | docker compose -p sd-book-my-show -f compose.yml -f compose.failover.yml exec -T postgres psql -U booking_demo -d booking -v ON_ERROR_STOP=1 -v "run_id=$($holdDockerFixture.runId)"
```

All six violation counts should be0; compare run row count with client results.
Let the observer finish. Loader exits on completion; neither group needs `down`,
volume removal, prune or file cleanup. Compose can warn about the retained one-off
test container; no remove-orphans action is needed. Use a new run/control name for
another explicitly selected run, and retain existing uncertain identities.

## Actual result — 2026-10-06

Run `d95d073367e9dae0`, started17:17 IST.100 requests/100 successful201 holds,
zero conflicts/errors/retries/replays/unresolved, amplification1.0. Each of the ten
one-second buckets contained exactly10 requests. Last response completed at9.912s.

| Observed successful-write HTTP latency | Milliseconds |
| --- | --- |
| Average |7.95 |
| p50 |7.04 |
| p95 |11.63 |
| p99 |21.47 |

All six SQL invariant violation counts0;100 HELD records at audit. Loader exit0,
server observer completed40s without a threshold stop. The retained artifacts are
under `load-test/results/docker-smoke-20261006-1718` and its observer/control
directories. [Tracked evidence](evidence/DOCKER_10RPS_2026-10-06.json) includes
summaries, per-second rates and metadata. No captured bodies/tokens.

Both Compose files validated;28 existing Node tests passed inside the resource
limited load-test container. Artifact/links/whitespace validation is recorded in
worklog. Maven was not rerun in this follow-up: Java/backend behavior is unchanged;
the earlier69-test verification retains its original evidence/date.

This is a successful small local Docker smoke, not a higher-throughput capacity,
Ankita/Tailscale or1 GiB improvement comparison. No new admission/cache/SSE claim.

## Phase3: Redis availability, later

Redis is explicitly deferred. The proposed container budget is500 MiB when that
phase is separately built. No Redis service, cache, pub/sub or push endpoint was
added, and PostgreSQL remains the authoritative seat owner.

Phase3 should study fast committed availability projections and browser refresh/
push separately from reservations. Cached availability can be stale; the hold
transaction must still validate ownership/expiry. Define version/freshness,
cache reconstruction and failure behavior before claiming quick/high-throughput
updates. Movie transition publishing/streaming remain unbuilt; the generic outbox
does not automatically publish movie changes.

Exercise: predict why a Redis AVAILABLE view cannot authorize a hold after another
buyer commits. Then inspect the ten request buckets and compare HTTP with logical
latency to locate the journal/scheduler overhead in this local run.
