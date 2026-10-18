# Local Docker deployment — 2026-10-18

User selected deployment of all local components after the isolated saga delivery.
The booking stack and idle Java load-tester control service are now running.
This supersedes earlier statements that the retained stack was not deployed;
those statements remain the historical implementation/test record.

## Running components

| Component | Local endpoint |
| --- | --- |
| HAProxy booking gateway | http://127.0.0.1:8132 |
| API A / API B, including saga workers | http://127.0.0.1:8130 / http://127.0.0.1:8131 |
| PostgreSQL | `127.0.0.1:5553`, database `booking` |
| Kafka | `localhost:9094`; Docker clients use `kafka:9092` |
| Copied mock payment gateway | http://127.0.0.1:8136/mock-pg/health |
| Legacy generic-booking provider | http://127.0.0.1:8133/health |
| Idle Java load-tester control service | http://127.0.0.1:8135/actuator/health |

The `kafka-topics` initializer completed with exit code 0. All four saga topics
have three partitions, replication factor one and `retention.ms=1209600000`.
The `booking-saga-v1` consumer group is Stable with two members and nine assigned
partitions across its three subscribed topics.

The Node smoke containers and retained one-off Java containers remain stopped.
The Node entrypoint immediately generates workload, so deployment did not start it.
The Java control service is running with a deliberate observer STOP marker at
`load-test/results/deployment-idle/STOP`; no observer or workload was started.
A future authorized run needs a fresh observer and its own control path. The
recreated control service now uses the committed 384 MiB / 1 CPU budget.
Redis remains deferred; there is no frontend component in this repository.

## Credentials and retained state

Separate randomly generated API/provider tokens are stored locally in
`target/local-docker/order-saga.env`, covered by the existing `target/` ignore rule.
No token values are included in this report. Use that env file for subsequent
Compose commands and the `ORDER_API_TOKEN` value for trusted development callers.
Order calls also require `X-Customer-Id`; see the [saga runbook](ORDER_SAGA_RUNBOOK.md).
Recovery controls remain disabled. All published ports bind to loopback.

The existing `sd-book-my-show_booking-data` volume and PostgreSQL container were
reused. Before migrations/API startup, `pg_dump -Fc` saved the database to
`target/local-docker/booking-pre-deploy-20261018-140423.dump` (39,559,776 bytes).
`pg_restore -l` successfully read its archive table of contents; a restore was not
performed. A copy remains in the PostgreSQL container's `/tmp` directory.
New retained volumes hold Kafka logs and legacy provider state.

Normal movie scheduling remains enabled on both APIs. Its existing policy
refreshes today..today+3 and purges eligible past-day legacy data; saga-linked
shows remain protected. The backup preserves the pre-startup database snapshot.
No manual data cleanup, volume removal, filesystem deletion, Maven clean or
operation on an unrelated project's stack was performed.

## Build and verification

- Repackaged the current booking source with `mvn -B -ntp -DskipTests package`;
  BUILD SUCCESS. The earlier jar predated one Java source file.
- Built both API images, the legacy provider and copied payment gateway. Removed
  the gateway Dockerfile's eager `dependency:go-offline` step after observing it
  resolve unrelated plugin/cloud dependencies. The existing package-time Maven
  cache is retained; the subsequent gateway package step succeeded in about 20 s.
- Started base + failover + provider + order-saga overlays with `--wait`.
- Readiness/health HTTP checks returned 200/UP for both APIs, HAProxy, the copied
  gateway and Java control service; legacy provider returned 200/`up`.
- Six gateway `/health` reads alternated between API A and API B. Movie browsing
  returned the 20-film slate.
- Order GET through HAProxy rejected an invalid token (401) and reached the
  owner-scoped missing-order result with the valid token (404). Copied gateway
  operation GET similarly returned 401 without credentials and 404 with them.
- PostgreSQL reports successful public migrations V1–V8 and gateway migrations
  V066/V068/V069 in its separate schema. Orders/provider saga operations were
  both zero after checks; no booking/payment POST was issued.

These are deployment/read-only checks. Maven suites, business smoke bookings,
fault experiments and sustained load were not run for this request. Earlier
[isolated correctness results](ORDER_SAGA_VERIFICATION.md) retain their original
execution dates and do not replace an end-to-end booking check of this deployment.
Local build/start logs and HTTP evidence are under `target/local-docker/`;
the [deployment snapshot](evidence/LOCAL_DOCKER_DEPLOYMENT_2026-10-18.json) excludes
credentials. Initial Kafka coordinator discovery settled into the Stable group.

## Inspect, restart or pause — PowerShell

Run from `D:/sd-book-my-show`:

Load tests require an explicit user request for that run. Normal application
startup/restart excludes both load generators, including the idle Java control
service. The running-component description above records the earlier deployment;
it does not authorize starting a loader again.

```powershell
$localCompose = @('--env-file', 'target/local-docker/order-saga.env',
  '-f', 'compose.yml', '-f', 'compose.failover.yml',
  '-f', 'compose.provider.yml', '-f', 'compose.order-saga.yml')
docker compose @localCompose ps --all
docker compose @localCompose up -d --no-build --wait --wait-timeout 240
Invoke-RestMethod http://127.0.0.1:8132/actuator/health/readiness

# Operational pause, preserving containers and volumes.
docker compose @localCompose stop
docker compose -f load-tester-java/compose.local.yml stop
```

Only when the user explicitly requests starting the Java loader control service:

```powershell
# Starts an idle control service; a workload still requires an explicit request.
$env:JAVA_LOADER_OBSERVER_STOP_FILE = '/results/deployment-idle/STOP'
docker compose -f load-tester-java/compose.local.yml up -d
```

V8 is applied. For recovery/rollback, preserve saga-aware binaries and evidence;
follow the [saga pause/retention procedure](ORDER_SAGA_RUNBOOK.md#pause-rollback-and-retention).
Do not downgrade to old movie maintenance writers while saga-owned bookings exist.
