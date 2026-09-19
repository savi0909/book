# Verification evidence

## Foundations tutorial documentation check — 2026-10-03

Added [the foundations tutorial](DISTRIBUTED_SYSTEMS_FOUNDATIONS.md) and README
navigation for the learner's ticket-booking-first study of databases, locking,
retry safety and retry storms. Inspected current Java/SQL/tests, original locking
and Python retry-storm source, and downloaded WAL/retry articles; used official
references for clarification. Optimistic seat versions and a controlled storm
harness remain proposals; application behavior and infrastructure are unchanged.

`node scripts/validate-artifacts.mjs` passed required-artifact, exported Postman
script parsing and local Markdown target checks. `mvn -B -ntp validate` at the
workspace passed all 21 reactor entries. Additional changed-document checks
passed code-fence balance, local links, ticket focus and named test references;
`git diff --check` passed. No application test, concurrency experiment or workload
was rerun for this documentation task. The execution evidence below is historical.

## Application verification — 2026-10-01

Executed 2026-10-01 on Windows 11/PowerShell, Java 21.0.9 (Zulu), Maven 3.9.11,
Docker Desktop engine 29.7.2, Node 24.11.0 and PostgreSQL 16.15 from postgres:16-alpine.
Spring Boot 3.5.16 resolved and built successfully. Read [spec](SYSTEM_SPEC.md)
and [parity](../PARITY.md) for assumptions and source relationships.

| Executed command | Observed result |
| --- | --- |
| `java -version`; `mvn -version`; port/runtime inspection | Java21/Maven available; isolated ports 8105/8106/5547 selected |
| `mvn -B -ntp verify` | 21 tests, zero failures/errors/skips; real PostgreSQL Testcontainer and random-port HTTP |
| `docker compose config --quiet`; `docker compose build api-a api-b`; `docker compose up -d --wait` | Valid config; two API images built; both APIs/database healthy |
| `node scripts/learn-booking.mjs` | Final 108 checks including polls; passed with restoration complete |
| Newman 6.2.2 full ordered collection | 57 named requests, 74 executed requests including bounded polls, 109 assertions; zero failures |
| `node scripts/validate-artifacts.mjs` | 16 required files, 57 named requests, 59 parsed script blocks, 26 Markdown files, 484 local links; zero broken targets |

Integration coverage: 24 distinct buyers/one seat; 16 same-key hold requests; buyer
key scoping/changed input; expired replay/reclaim; 12 duplicate checkouts; success,
failure and unknown recovery; late success after reassignment; cancellation before
success and after confirmation; duplicate callbacks/audit once; conflicting outcome
and event ID reuse; long provider delay without inventory lock; expiry at SQL check
after waiting for a lock; abandoned lease recovery/stale completion; direct constraint
rejection; HTTP statuses, missing key, fractional/bounded input, invalid UUID/pages,
missing resource/error envelope and normal health.

Runtime uses two actual JVM containers and one PostgreSQL authority: 24 distinct
buyers yielded exactly one new hold/23 conflicts; 20 concurrent duplicate holds
yielded one new booking; 12 duplicate checkouts yielded one payment; 12 callbacks
left one confirmation audit. Late success did not steal a replacement hold.
Both APIs stopped after unknown acceptance; PostgreSQL restarted with retained
volume; same intent recovered and request key replay returned the original booking.
Brief PostgreSQL pause returned bounded 503 for a data route while process health
remained 200, followed by recovery. Counts include polling and vary by timing.
No throughput or latency SLO was measured. Postman is an API execution check,
not Postman desktop UI or browser verification.

Initial errors: direct field access on the Spring repository proxy caused null
JDBC references; changed to an accessor before the final green suite. Initial
runtime scenario checks passed but its finally block raced Docker's cached
unhealthy status after unpause; added health-probe wait and reran successfully.
Priority/cancellation context was reconciled and checked. Mockito/ByteBuddy dynamic-agent
warnings and expected Hikari/socket warnings during the pause were observed.

Evidence under ignored target/: verification-maven.log, compose-build.log,
runtime-console.log, runtime-evidence.json, postman-console.log and
postman-evidence.json. Docs preserve the durable summary. No original comparison
was run because no booking baseline exists; related source inspection is in parity.

Retained resources: ticket-booking-java API A8105/B8106/PostgreSQL5547 on loopback,
ticket-booking-java_booking-data volume, fresh runtime/Postman events, bookings,
keys, receipts and audits. Other projects/original sources preserved. Stop with
`docker compose stop`; no files/directories deleted, clean/reset/prune or global
cleanup used. Testcontainers manages its own ephemeral test infrastructure.

Not established: learner mastery, sustained load/admission fairness, real provider
availability/charges/refunds, independent-provider outage, callback authentication,
customer ownership, regional routing, PostgreSQL replication/failover, power-loss
durability, bounded archival, group booking or fraud/currency behavior. The simulator
shares PostgreSQL and only approximates the separately committed acceptance gap.
Ordinary restart evidence is not a claim about failover or distributed ACID.
