# Ticket booking interview lab

Two Spring Boot replicas compete for numbered seats in PostgreSQL. Temporary
holds, durable request keys, expiry, checkout outcomes and late-payment refunds
make the no-oversell invariant observable. This is a new interview lab; related
original sources are listed in [PARITY.md](PARITY.md).

Java 21, Spring Boot 3.5.16 and Maven; JDBC keeps SQL locking visible. One executable
application, one PostgreSQL database, no Redis or broker. All buyers and provider
controls are unauthenticated local fixtures. The provider is simulated; no money
is charged and no card data is accepted.

```powershell
Set-Location D:/java-projects/ticket-booking-lab
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
| Replica A | http://localhost:8105 |
| Replica B | http://localhost:8106 |
| PostgreSQL | localhost:5547, database booking; public demo login booking_demo/booking_demo |

For a host-only API, start just PostgreSQL with `docker compose up -d --wait postgres`,
then `mvn spring-boot:run`. Default JDBC configuration uses port 5547. Do not also
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

[Scenario 4 — transactional outbox, inbox and event ordering](docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md): atomic confirmation/cancellation snapshots, recoverable dispatcher, duplicate-safe local receipts, versioned projection, actual crash experiments and interview points.

[Scenario 3 — poison-job isolation and controlled redrive](docs/POISON_JOB_TUTORIAL.md): per-item failures, persistent quarantine/history, bounded keyed redrive, safe late-success reconciliation, detailed walkthrough and interview points.

[Scenario 2 — payment-provider isolation](docs/PROVIDER_ISOLATION_TUTORIAL.md): independent retained Java stub, bounded calls/work, deadlines, breaker, durable retry budget and backlog admission. Detailed walkthrough and interview points.

[Next-session handover](docs/NEXT_SESSION_HANDOVER.md): review completed scenarios1–4; later concepts remain separate proposals.

[Scenario 1 — API failover and graceful restart](docs/API_FAILOVER_TUTORIAL.md): implemented optional HAProxy setup, deterministic crash/drain experiments, detailed source walkthrough, and five interview points. Provider and poison-job isolation have separate studies.

[Failure-handling and high-availability scenarios](docs/FAILURE_SCENARIOS.md): six prioritized additions, concrete failure injections, expected guarantees, and local source references.

[Distributed systems foundations through ticket booking](docs/DISTRIBUTED_SYSTEMS_FOUNDATIONS.md): database internals, pessimistic and proposed optimistic locking, safe retries, retry storms, and an ordered learning track using this booking domain.

[Detailed IntelliJ project study guide](docs/INTELLIJ_CODE_STUDY_TUTORIAL.md): ordered source reading, API/event traces, debugger checkpoints, exercises, tests, and engineering tradeoffs.

## Optional provider-isolation study

Historical provider host8123 currently conflicts with URL-shortener API C. Check
port ownership before starting that overlay; scenario3 uses the default simulator
and requires no new port. See [the poison tutorial](docs/POISON_JOB_TUTORIAL.md)
for base+failover+poison commands and manual maintenance ticks.

Use all three files to retain failover controls and enable the independent stub:

```powershell
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml config --quiet
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml build api-a api-b provider
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml up -d --wait
node scripts/learn-provider.mjs
npx --yes newman@6.2.2 run postman/provider.postman_collection.json -e postman/provider.postman_environment.json --delay-request 500
```

Provider: loopback8123 (container8121), retained provider-data journal. Public local
fault controls; no real payments. Two provider-call slots per API, two actual
processing slots at the stub, 400ms HTTP deadline, four automatic dispatches or
10s dispatch budget; exhausted UNKNOWN needs explicit reconciliation. New checkout
pauses at100 unresolved or30s oldest age; hold/browse and existing-key replay remain
available. See [tutorial](docs/PROVIDER_ISOLATION_TUTORIAL.md) and [API](docs/API_REFERENCE.md).
Original booking collection/harness use the default simulator, whose unlimited
polling contract differs from this overlay. Run fault exercises sequentially.
Stop/resume with the same files and stop/up; preserve both volumes.
