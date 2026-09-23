# Verification evidence

## Scenario 1: API failover and graceful restart — 2026-10-04

Delivered [the detailed tutorial](API_FAILOVER_TUTORIAL.md), optional HAProxy
overlay, bounded local failure controls, Java/PostgreSQL integration coverage,
Postman collection and Docker crash/drain experiment. Scenarios 2 and 3 remain
queued for separate deliveries. Guide authorship does not establish learner mastery.

| Executed check | Observed result |
| --- | --- |
| `mvn -B -ntp verify` | 25 tests, zero failures/errors/skips, real PostgreSQL Testcontainers |
| `docker compose -f compose.yml -f compose.failover.yml config --quiet`, `build api-a api-b`, `up -d --wait` | Configuration valid; APIs built; database and both APIs healthy |
| `docker compose -f compose.yml -f compose.failover.yml exec -T gateway haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg` | Live gateway configuration accepted; HAProxy 3.2.25, digest pinned in overlay |
| `node scripts/learn-failover.mjs` | Final run: 34 explicit checks, crash and graceful-stop paths passed; restoration true |
| Newman 6.2.2 failover collection/environment | 17 requests, 26 assertions, zero failures |
| Newman 6.2.2 original booking collection/environment | 74 executed requests, 109 assertions, zero failures |
| Workspace `mvn -B -ntp validate` | All 21 reactor entries passed |
| `node scripts/validate-artifacts.mjs` | 23 required artifacts, 74 named requests, 77 parsed script blocks, 30 Markdown files/643 local links; zero broken targets |
| `git diff --check` and changed Markdown fence check | Passed |

Final runtime run: 2026-10-04 08:58:50–08:59:14 UTC. Abrupt termination lost the
original checkout response (harness status 0 denotes a fetch/connection failure,
not an HTTP status). Recovery through B returned the same payment ID; SQL showed
one payment row and the audit showed one checkout transition. B also accepted a
fresh booking. Observed routing recovery was 1117ms, including command and polling
overhead; this is a single observation, not a failover SLO.

During graceful stop, A rejected new work and withdrew readiness while remaining
live; B accepted fresh work. A's admitted delayed response completed with 202.
The stop took 7038ms and exited 143 (SIGTERM), with no OOM. This validates response
completion for this bounded request, not every shutdown timing or maintenance job.
When both replicas were drained, the gateway returned a bounded 503.

Java checks cover drain/readiness/liveness, rejection before mutation, committed
intent visible during response delay, completion after drain, same-payment replay,
delay bounds, and controls disabled by default. Existing concurrency/payment tests
remain green. No new schema, actual payment provider or database HA was introduced.

Ignored local evidence: `target/failover-maven.log`,
`target/failover-runtime-evidence.json`, `target/failover-postman-evidence.json`,
`target/failover-regression-postman-evidence.json`, associated console logs and
`target/workspace-validate-failover.log`. Both API replicas and the gateway were
restored accepting traffic; PostgreSQL and existing data remain retained. Each
runtime execution creates four fresh event/hold fixtures. A stopped container
named `booking-haproxy-config-check` is retained from config validation; no files,
containers or volumes were deleted. Other learning stacks were not changed.

Local Java 21/Maven/Node/Docker environment remains as described below. No load,
database failover, host/zone failure, browser UI or production authentication test
was performed. One gateway, PostgreSQL primary and host remain failure domains.

Final command-check correction: an unsupported `--help` invocation from the
workspace root began a partial experiment, created one event/hold and committed
its checkout, then failed to locate Compose before any process kill. Both APIs
were explicitly resumed and verified accepting with no waiting responses. Added
and checked preflight rejection for a wrong working directory and unsupported
arguments; `--help` now exits without running the experiment. The partial-run
record is retained as `target/failover-wrong-directory-evidence.json`; the complete
passing run remains `target/failover-runtime-evidence.json`. No evidence was deleted.

## Failure-scenario suggestions documentation check — 2026-10-04

Added [six proposed scenarios](FAILURE_SCENARIOS.md), linked from README and the
foundations tutorial. Inspected current batch/recovery/refund/configuration code,
local failover/breaker/poison examples and the archived poison-pill article, then
official Spring/PostgreSQL/outbox references. Artifact/link checks, six-scenario
structure, code-fence balance and whitespace validation passed. Application code,
infrastructure and runtime tests were not changed or exercised. This is a set of
recommendations; no scenario was selected for implementation.

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
