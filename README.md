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
Production auth, actual provider integration, refunds, admission control and
database failover remain outside the local lab. A database outage rejects operations;
the application does not manufacture availability from an in-memory seat cache.

## Code study

[Failure-handling and high-availability scenarios](docs/FAILURE_SCENARIOS.md): six prioritized additions, concrete failure injections, expected guarantees, and local source references.

[Distributed systems foundations through ticket booking](docs/DISTRIBUTED_SYSTEMS_FOUNDATIONS.md): database internals, pessimistic and proposed optimistic locking, safe retries, retry storms, and an ordered learning track using this booking domain.

[Detailed IntelliJ project study guide](docs/INTELLIJ_CODE_STUDY_TUTORIAL.md): ordered source reading, API/event traces, debugger checkpoints, exercises, tests, and engineering tradeoffs.
