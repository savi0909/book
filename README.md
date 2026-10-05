# SD Book My Show

Resume entry: [project context](PROJECT_CONTEXT.md), [worklog](docs/WORKLOG.md)
and [agent instructions](AGENTS.md).

Movie-booking backend with multiplexes, dated shows, category prices, atomic
group reservations and durable mock payment retries. Start with the
[movie walkthrough](docs/MOVIE_BOOKING_TUTORIAL.md),
[movie API reference](docs/MOVIE_API_REFERENCE.md),
[verification](docs/MOVIE_VERIFICATION.md),
[future-work handoff](docs/FUTURE_WORK.md) and
[later simulation plan](docs/MOVIE_LOAD_SIMULATION_PLAN.md).
The inherited generic single-seat studies below remain separately available.

[100K hot-show, live availability and retry-storm plan](docs/HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md):
study existing provider protections first, then separately compare bounded immediate
and jittered hold retries. Availability streaming/admission remain proposals.
Future experiments: Abhishek hosts services; Ankita sends load over Tailscale.
Latest direction: documentation only; do not implement or run load yet.

Standalone Java ticket-booking study project at `D:/sd-book-my-show`. Start with
[standalone setup](docs/STANDALONE_SETUP.md) and the
[three-week sprint plan](docs/THREE_WEEK_SPRINT_PLAN.md).
Its reconstructed September 15-October 5 history is explained in
[timeline provenance](TIMELINE_PROVENANCE.md); original verification dates are retained.

The copied tutorials describe the original lab's addresses. This repository uses
API A8130, API B8131, gateway8132, provider8133 and PostgreSQL5553. Its scripts and
Postman environments use these standalone addresses. Original evidence and tutorial
experiment dates describe the source lab; see standalone verification for this copy.

Two Spring Boot replicas compete for numbered seats in PostgreSQL. Temporary
holds, durable request keys, expiry, checkout outcomes and late-payment refunds
make the no-oversell invariant observable. This is a new interview lab; related
original sources are listed in [PARITY.md](PARITY.md).

Java 21, Spring Boot 3.5.16 and Maven; JDBC keeps SQL locking visible. One executable
application, one PostgreSQL database, no Redis or broker. All buyers and provider
controls are unauthenticated local fixtures. The provider is simulated; no money
is charged and no card data is accepted.

```powershell
Set-Location D:/sd-book-my-show
java -version
mvn -version
mvn -B -ntp verify
docker compose config --quiet
docker compose build api-a api-b
docker compose up -d --wait
node scripts/learn-booking.mjs
npx --yes newman@6.2.2 run postman/ticket-booking-lab.postman_collection.json -e postman/local.postman_environment.json
```

Tests require a working Docker engine for real PostgreSQL Testcontainers. Runtime
experiments briefly stop/restart this project's APIs/database and pause its database;
run them separately from the Postman collection. Every run creates fresh retained
fixtures. Stop without deleting data: `docker compose stop`. Resume:
`docker compose up -d --wait`. Do not use clean/reset/prune or remove volumes.

| Local service | Address |
| --- | --- |
| Replica A | http://localhost:8130 |
| Replica B | http://localhost:8131 |
| PostgreSQL | localhost:5553, database booking; public demo login booking_demo/booking_demo |

For a host-only API, start just PostgreSQL with `docker compose up -d --wait postgres`,
then set `$env:PORT="8130"` and run `mvn spring-boot:run`. Default JDBC configuration uses port 5553. Do not also
run Compose API A on the same host port. No Maven wrapper is included.

Start with [the user guide](docs/USER_GUIDE.md). Read [the system specification](docs/SYSTEM_SPEC.md)
for invariants and transaction traces, [the API reference](docs/API_REFERENCE.md) for
contracts, [the interview guide](docs/INTERVIEW_GUIDE.md) for a 45-minute practice
outline and [verification](docs/VERIFICATION.md) for actual evidence and limits.
Import [the collection](postman/ticket-booking-lab.postman_collection.json) with
[the local environment](postman/local.postman_environment.json).

Implementation entry: [BookingService](src/main/java/com/example/booking/BookingService.java),
[PaymentProcessor](src/main/java/com/example/booking/PaymentProcessor.java),
[migration](src/main/resources/db/migration/V1__booking.sql) and
[integration tests](src/test/java/com/example/booking/BookingIntegrationTest.java).
Production auth, actual provider integration, refunds, throughput admission control and
database failover remain outside the local lab. A database outage rejects operations;
the application does not manufacture availability from an in-memory seat cache.

## Code study

[API 3 — get one event](docs/API_03_GET_EVENT_WALKTHROUGH.md): path UUID, the row-mapper lambda and the missing-event path. Import [its single-request Postman collection](postman/api-03-get-event.postman_collection.json) and paste an existing event ID.

[API 1 lambda execution walkthrough](docs/API_01_LAMBDA_EXECUTION_WALKTHROUGH.md): who calls each lambda, what `work.get()` does, return flow and IntelliJ breakpoints.

[API 2 — list events](docs/API_02_LIST_EVENTS_WALKTHROUGH.md): query parameters, SQL ordering, pagination and row mapping. Import [its single-request Postman collection](postman/api-02-list-events.postman_collection.json).

[Start with API 1 — create a demo event](docs/API_01_CREATE_EVENT_WALKTHROUGH.md): one endpoint at a time, short IntelliJ reading steps, the request-to-SQL path and one small exercise.

Import [the API 1 learning collection](postman/api-01-create-event.postman_collection.json) for one ready-to-send request; its local URL is included. The [full booking collection](postman/ticket-booking-lab.postman_collection.json) remains available with the [local environment](postman/local.postman_environment.json).

[Scenario 4 — transactional outbox, inbox and event ordering](docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md): atomic confirmation/cancellation snapshots, recoverable dispatcher, duplicate-safe local receipts, versioned projection, actual crash experiments and interview points.

[Scenario 3 — poison-job isolation and controlled redrive](docs/POISON_JOB_TUTORIAL.md): per-item failures, persistent quarantine/history, bounded keyed redrive, safe late-success reconciliation, detailed walkthrough and interview points.

[Scenario 2 — payment-provider isolation](docs/PROVIDER_ISOLATION_TUTORIAL.md): independent retained Java stub, bounded calls/work, deadlines, breaker, durable retry budget and backlog admission. Detailed walkthrough and interview points.

[Next-session handover](docs/NEXT_SESSION_HANDOVER.md): review completed scenarios1–4; later concepts remain separate proposals.

[Scenario 1 — API failover and graceful restart](docs/API_FAILOVER_TUTORIAL.md): implemented optional HAProxy setup, deterministic crash/drain experiments, detailed source walkthrough, and five interview points. Provider and poison-job isolation have separate studies.

[Failure-handling and high-availability scenarios](docs/FAILURE_SCENARIOS.md): six prioritized additions, concrete failure injections, expected guarantees, and local source references.

[Distributed systems foundations through ticket booking](docs/DISTRIBUTED_SYSTEMS_FOUNDATIONS.md): database internals, pessimistic and proposed optimistic locking, safe retries, retry storms, and an ordered learning track using this booking domain.

[Detailed IntelliJ project study guide](docs/INTELLIJ_CODE_STUDY_TUTORIAL.md): ordered source reading, API/event traces, debugger checkpoints, exercises, tests, and engineering tradeoffs.

## Optional provider-isolation study

The standalone provider uses host8133, separate from the original lab and URL
shortener. Scenario3 uses the default simulator and requires no new port. See [the poison tutorial](docs/POISON_JOB_TUTORIAL.md)
for base+failover+poison commands and manual maintenance ticks.

Use all three files to retain failover controls and enable the independent stub:

```powershell
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml config --quiet
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml build api-a api-b provider
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml up -d --wait
node scripts/learn-provider.mjs
npx --yes newman@6.2.2 run postman/provider.postman_collection.json -e postman/provider.postman_environment.json --delay-request 500
```

Provider: loopback8133 (container8121), retained provider-data journal. Public local
fault controls; no real payments. Two provider-call slots per API, two actual
processing slots at the stub, 400ms HTTP deadline, four automatic dispatches or
10s dispatch budget; exhausted UNKNOWN needs explicit reconciliation. New checkout
pauses at100 unresolved or30s oldest age; hold/browse and existing-key replay remain
available. See [tutorial](docs/PROVIDER_ISOLATION_TUTORIAL.md) and [API](docs/API_REFERENCE.md).
Original booking collection/harness use the default simulator, whose unlimited
polling contract differs from this overlay. Run fault exercises sequentially.
Stop/resume with the same files and stop/up; preserve both volumes.
