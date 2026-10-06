# Verification evidence

2026-10-06 [client-module verification](HOLD_LOAD_TEST_VERIFICATION.md):69 Maven
tests/28 Node tests passed, including actual client with isolated PostgreSQL
committed-loss discovery and preserved movie tests. Observer/audit smoke passed.
User-authorized small local comparison and exhaustion/discovery checks passed
with zero SQL violations. Remote Ankita workload pending. Historical evidence
retains its original dates.

## Standalone movie expansion - 2026-10-05

Read [movie verification](MOVIE_VERIFICATION.md) for V5,68 tests,23 A/B runtime
checks,30 movie Postman requests/60 assertions and75 generic regression requests/
110 assertions. Original dates/evidence below are preserved source-lab history.

## API 3 get-event study - verified2026-10-05

Added [the focused walkthrough](API_03_GET_EVENT_WALKTHROUGH.md) and
[single-request collection](../postman/api-03-get-event.postman_collection.json).
Read one retained event ID through GET /api/events?limit=1&offset=0, then ran
`npx --yes newman@6.2.2 run postman/api-03-get-event.postman_collection.json --env-var "eventId=<existing UUID>"`:
one collection request, HTTP200, five assertions, zero failures. Checks cover
JSON, object shape, matching UUID and metadata. Missing/malformed-ID behavior
in the tutorial was traced through source, not separately exercised in this run.
Artifact checks cover collection shape, scripts and local links. Application
code, original collections, runtime stack and retained data preserved; no writes.

## API 2 list-events study - verified2026-10-05

Added [the focused walkthrough](API_02_LIST_EVENTS_WALKTHROUGH.md) and
[one GET request collection](../postman/api-02-list-events.postman_collection.json).
Executed `npx --yes newman@6.2.2 run postman/api-02-list-events.postman_collection.json`
against running replica A: one request, HTTP200, six assertions, zero failures.
Checks cover JSON/array shape, page-size bound, event fields and descending
timestamps; no independent UUID tie-order or fixture-presence assertion.
No application changes, fixture writes or stack operations. Artifact validator
also checks this collection's shape, script syntax and documentation links.

## API 1 Postman learning collection - verified2026-10-05

Added [a single-request importable collection](../postman/api-01-create-event.postman_collection.json)
for POST /api/demo/events, with a collection-local baseUrl and saved eventId.
Executed `npx --yes newman@6.2.2 run postman/api-01-create-event.postman_collection.json`
against the already-running replica A: one request, HTTP201, five assertions,
zero failures. This created one retained event fixture; no stack changes.
Checks cover returned request fields, UUID, JSON and database default fields,
not an independent seat-row count. Artifact validation covers collection JSON,
script syntax and local documentation links. Original full collections preserved.

## Scenario4 transactional outbox - verified2026-10-05

User continued concepts implementation after3. Delivered additive V4, atomic
confirmation/cancellation snapshots, recoverable leased dispatcher, atomic local
inbox/receipt and monotonic booking projection, diagnostics, gated crash/reorder
controls and full incremental learning kit. [Tutorial](TRANSACTIONAL_OUTBOX_TUTORIAL.md)
and [compact evidence](evidence/OUTBOX_2026-10-05.json).

| Executed command/check | Actual result |
| --- | --- |
| mvn -B -ntp verify | 53 tests,0 failures/errors/skips; real PostgreSQL plus existing controlled-provider HTTP/failover regressions |
| base+failover+outbox config/build/up | Passed; retained booking DB migratedV3→V4, historical data retained |
| node scripts/learn-outbox.mjs | 26 actual Docker/HTTP checks passed,restoration true |
| outbox Newman6.2.2 | 26 requests/41 assertions,0 failures |
| poison Newman in poison overlay | 24 requests/35 assertions,0 failures |
| original booking Newman, scheduled default mode | 73 requests/108 assertions,0 failures; polling count varies |
| failover Newman, scheduled default mode | 17 requests/26 assertions,0 failures |
| root mvn -B -ntp validate | 21 reactor entries passed |
| artifact/Markdown/Postman-script validation | 56 required files/136 named requests/142 parsed script blocks;zero broken links; see JSON for final link count |

New11 PostgreSQL tests establish committed pending work; confirmation/outbox rollback
with separately committed provider receipt retained; cancellation/event rollback
and replay; consumer response loss after effect commit; duplicate-safe receipt;
cancellation-first stale confirmation suppression; inbox/effect rollback and16
concurrent duplicate consumes; active/stale lease fencing and free unrelated hold;
conservative shared-DB exception handling; no false confirmation on late success;
per-item failure continuation; required source transaction and lifecycle admission.
Existing test additionally checks absent-by-default outbox controls/available status.
The DB-failure unit path mocks sink failure after real PostgreSQL claim commit;
this delivery does not claim a new actual DB outage experiment.

Runtime SIGKILL1 occurs after source confirmation/outbox commit and before dispatch;
B observes the same source event and delivers it. SIGKILL2 occurs after local inbox/
receipt commit and before ack, verified through waitingEvents and B diagnostics.
The actual HTTP response is lost, live lease prevents a second claim, then B reclaims
after expiry: attempts2/inbox deliveries2/local receipts1, deliveredAt set. A/B race
claims one original event once. Cancellation2 consumed before confirmation1 leaves
projection CANCELLED2, stale inbox disposition and only cancellation receipt.
Gateway diagnostics200/control404 checked. These are finite correctness observations,
not measured throughput, email delivery, strict fairness or an SLO.

Final topology restored to base+failover A8105/B8106/gateway8107/PostgreSQL5547:
both scheduled maintenance and outbox dispatch enabled, study controls off,
default payment simulator; pending outbox0 and unresolved/quarantined payments0.
Retained historical provider container remains stopped because its8123 is owned
by URL-shortener C. All nine shortener services remained running/undisturbed.
Independent-provider runtime suite not rerun on conflicting port; its existing
Java/controlled-HTTP tests passed. Optional outbox overlay remains for manual study.

V4 success confirmed in retained flyway_schema_history.28 old CONFIRMED bookings
with delivery_version0 demonstrate the intentional pre-cutover boundary: no historical
event backfill or retrospective notifications. Newly written transitions use V4.
All booking/provider volumes and source/inbox/receipt/history fixtures retained.
No files/directories deleted, Maven clean, reset, prune or orphan removal.
Original IntelliJ edits/staged jpa.xml preserved and excluded. Canonical memory
updated locally, without Git metadata; Java task changes delivered through origin.

Raw ignored evidence: target/outbox-build.log, outbox-runtime-evidence.json,
outbox-newman-evidence.json, outbox-poison-regression.json,
outbox-booking-regression.json, outbox-failover-regression.json,
outbox-reactor.log and artifact-evidence.json. Compose orphan warning concerns
intentionally retained provider; no removal. Newman fs.F_OK deprecation warning
has no failed assertions. Original local JavaScript reference inspected, not executed;
official AWS source consulted after local discovery, no original contract parity.

Limits: same PostgreSQL availability domain for source/sink despite separate commits;
local notification ledger only, no broker/real email or external exactly-once. Monotonic
snapshot projection does not guarantee FIFO/delta semantics or read-after-cancel
before cancellation arrives. Outbox retries have no exhaustion/quarantine policy;
new permanent delivery bugs need investigation. Existing default scheduler thread
is shared with payment/expiry work. Old source writers ignore outbox and must drain
before rollout. No production auth/HA/load/SLO/learner-mastery claim.1–4 complete;
stop.5–6/optimistic seats/general retry-storm harness remain separate proposals.

## Scenario 3 poison-job isolation - verified 2026-10-04

Delivered additive V3, per-claimed-item exception isolation, independent maintenance
phase catches, durable quarantine/history, token/lease fencing and keyed bounded
original-ID redrive. Tutorial: [poison-job study](POISON_JOB_TUTORIAL.md).
Compact committed summary: [evidence JSON](evidence/POISON_ISOLATION_2026-10-04.json).

| Command/check | Actual result |
| --- | --- |
| mvn -B -ntp verify | 42 tests,0 failures/errors/skips; real PostgreSQL Testcontainers; existing provider HTTP and failover tests included |
| merged base+failover+poison config/build/up | Passed; existing booking database upgraded V2→V3; data retained |
| node scripts/learn-poison.mjs | 23 checks passed, restoration true |
| poison Newman6.2.2 | 24 requests/35 assertions,0 failures |
| original booking Newman, scheduled default simulator | 75 requests/110 assertions,0 failures; polling count can vary |
| failover Newman on final rebuilt images | 17 requests/26 assertions,0 failures |
| root mvn -B -ntp validate | 21 reactor entries passed |
| scripts/validate-artifacts.mjs, syntax/Compose/whitespace checks | Passed; see compact JSON for exact artifact/link counts |

Java coverage proves first failed candidate permits four later healthy confirmations,
three-failure automatic quarantine, immutable same-ID receipt reuse, cross-worker
duplicate redrive serialization, active/stale lease protection,2-redrive lifetime cap,
late-success refund/new owner safety, conservative DB/provider classification,
malformed provider timestamp handling, quarantine backlog age/admission with
existing-key replay, expiry-phase independence, disabled fixtures/default404 controls,
lost-response redrive key consumption/replay and invalid-key/missing-fixture handling.
DB-failure injection in the Java item test uses a mocked provider throwing Spring's
infrastructure exception **after real PostgreSQL claim commit**; runtime additionally
pauses/unpauses the actual retained PostgreSQL and observes503 with no false quarantine.

Runtime uses both actual API containers and default simulator. The fixture throws
after receipt commit. It records exactly3 item failures/dispatches before quarantine,
restarts A and observes retained quarantine through both APIs, races same-key redrive
on A/B and observes only one extra dispatch/one confirmation audit. It separately
expires/reassigns a seat before late redrive and observes REFUND_REQUIRED while the
replacement remains HELD. Broken redrives stop at5 total attempts; same-key replay
does not increment; a verified immutable callback resolves the retained obligation.
No sustained-load, throughput or recovery-lag SLO claim follows from these finite checks.

Final images rebuilt after final Java validation. Final topology is **base+failover**:
A8105/B8106/gateway8107/PostgreSQL5547, automatic maintenance enabled, poison fixture
controls absent, default simulator, zero unresolved/quarantined/exhausted backlog and
open checkout admission. Historical provider container/journal stopped and retained.
Provider overlay's8123 is now owned by URL-shortener API C: its runtime suite was not
rerun on that port; existing provider Java/controlled-HTTP tests passed. All nine
URL-shortener services remain running; none were restarted/paused by this task.
The poison overlay is available for learner-controlled deterministic manual ticks.

Raw ignored evidence: target/poison-final-build.log, poison-runtime-evidence.json,
poison-newman-evidence.json, poison-booking-regression.json,
poison-failover-regression.json, poison-reactor.log and artifact-evidence.json.
Fresh event/booking/payment/receipt/history/audit fixtures and both prior volumes
are retained. No file deletion, Maven clean, reset, prune or orphan removal.
Preexisting IntelliJ edits/staged jpa.xml preserved and excluded from task commit.
Canonical memory updated locally; that reference tree has no Git metadata.

Corrections: fixed a test-constructor type mismatch before passing validation;
classified malformed provider timestamps as dependency errors; corrected missing
fixture404 handling. Compose reports the intentionally retained stopped provider
as an orphan; no removal performed. Newman emits Node's fs.F_OK deprecation warning
without assertion failures. Historical scenario1/2 evidence below remains historical.

Limits: catches RuntimeException after claim, not process kills/OOM/CPU hangs or
pre-claim errors. All DB exceptions conservatively abort the batch, including
item-specific SQL errors. No starvation-freedom under unbounded arrivals. A crash
before durable failure recording does not increment itemFailures; remote mode
still has its separate durable dispatch cap. A redrive crash after admission
consumes its key/cap; replay discovers state without re-execution. Old workers
ignore quarantine and must drain before rollout. No external payments/refunds,
production auth/DB-host HA, exactly-once or production SLO/learner-mastery claim.
Scenarios1–3 complete; stop.4–6/optimistic seats/retry-storm harness remain proposals.

## Scenario 2 provider isolation - verified 2026-10-04

Delivered independent Java provider stub, provider overlay, V2 retry metadata,
bounded client/remote slots, deadlines, per-process breaker/probes/generations,
durable capped jitter/budget, shared backlog admission, diagnostics, tutorial,
Postman and scenario3 handover. Full committed summary:
[evidence JSON](evidence/PROVIDER_ISOLATION_2026-10-04.json).

| Command/check | Actual result |
| --- | --- |
| mvn -B -ntp verify | 29 Java tests passed,0 failures/errors/skips; real PostgreSQL and controlled HTTP dependency |
| merged Compose config/build/up | Passed; A/B/PostgreSQL/provider healthy and gateway running; data retained |
| node scripts/learn-provider.mjs | 22 checks passed, restoration true; independent SLOW/UNAVAILABLE/LOSS/restart |
| provider Newman6.2.2, `--delay-request 500` | 12 requests/13 assertions,0 failures |
| original booking Newman in default simulator mode | 74 requests/109 assertions,0 failures |
| node scripts/learn-failover.mjs | 34 checks passed, restoration true; crash, graceful drain and same-ID recovery |
| failover Newman6.2.2 | 17 requests/26 assertions,0 failures |
| root mvn -B -ntp validate | 21 reactor entries passed |
| script syntax/artifact/link/whitespace checks | 32 required files/86 named requests/89 parsed scripts/687 local links,0 broken |

New integration assertions cover two concurrent slow calls and immediate third
rejection; continued hold/browse; remote continuation after caller timeout;
same-ID recovery; independent attempt-count and elapsed-time exhaustion;
age-based checkout closure with replay discovery; one half-open probe,
failed-probe reopen and successful recovery; stale-generation completion fencing.
Runtime uses both APIs and the actual bounded standalone Java stub. At the slow
observation: provider active2/maximum2, API A inFlight0, unresolved2,
oldestSeconds1.00104. The unavailable fixture exhausted at4 dispatches with
lastError CIRCUIT_OPEN. Final backlog count/age/exhausted all zero. Runtime began
09:34:37.998Z and ended09:34:53.633Z; these finite observations are not an SLO or
throughput/drain-rate benchmark. Stub restart retained receipts. Late success
recorded REFUND_REQUIRED while replacement remained HELD.

Raw local evidence: target/provider-runtime-evidence.json,
provider-final-build.log, provider-newman-evidence.json,
provider-booking-default-regression.json, provider-failover-newman-regression.json
and failover-runtime-evidence.json. target is ignored; the compact summary above
is committed. Fresh fixtures and both booking/provider volumes are retained.

Corrections and boundaries:

- Initial provider host port8121 was already allocated to url-shortener's
  coordinator-b. Compose start failed to bind; it did not stop that project.
  Selected free loopback8123, rebuilt/restarted the selected stack successfully.
- Running the original unlimited-polling collection under the new bounded
  overlay produced188 requests/223 assertions,8 failed assertions and one script
  error. A delayed outcome exhausted before availability, then its unresolved
  age closed new checkout. This is an incompatible scenario assumption, not a
  green regression. Preserved target/provider-booking-overlay-incompatible.json,
  explicitly reconciled the affected UNKNOWN, and reran in the default topology:
  74/109 passed. The provider collection verifies the new contract separately.
- A documentation write hit Windows default text-encoding/newline behavior;
  corrected UTF-8/newlines and rechecked the scoped diff and Markdown links.
- Newman reports Node's existing fs.F_OK deprecation warning; no test failure
  is attributed to it.

Final topology: A8105/B8106/gateway8107/PostgreSQL5547/provider8123, both overlays
active, NORMAL provider mode, zero unresolved backlog and open checkout admission.
No files/directories deleted, Maven clean, reset, prune or unrelated stack changes.
Preexisting .idea edits/staged jpa.xml are preserved and excluded from this task.

Limits: HTTP fault tests delay before headers; arbitrary streaming-body deadlines
are not established. Two provider-processing slots cover one stub process, not a
provider fleet. Journal controlled restart verified; torn writes/storage/host crash
not tested. Count/age admission is not a per-hold lifetime guarantee. No production
auth, real payments/refunds, external exactly-once, DB/host HA, benchmark or SLO.
Unexpected poison exceptions can still interrupt a batch; scenario3 remains next.
Tutorial/test completion does not establish learner mastery.


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
