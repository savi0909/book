# SD Book My Show agent instructions

## Tutorial review and advance-booking tutorial — 2026-10-08

Start studying from the [study path](docs/PROJECT_STATUS.md#study-path). The new
[movie advance-booking tutorial](docs/MOVIE_ADVANCE_BOOKING_TUTORIAL.md) covers the
2026-10-07 work. [Review findings](docs/TUTORIAL_REVIEW_2026-10-08.md) list
what was corrected. Current facts that supersede the older sections below:
- Origin is https://github.com/savi0909/book; use a branch and PR, and merge only
  on GitHub.
- The Java loader has 19 tests (14 generic + 5 `MovieLoadTest`) and a movie runner.
- PostgreSQL is 3 GB / 2 CPUs.
- The loader's committed default is 1 CPU, but the retained container still has
  0.5 CPU and `-Xmx192m` until it is recreated.
- `MovieScheduleMaintainer` runs on **both** APIs (shared `&api-env` anchor).

Open code defect (not fixed, needs user approval): `scripts/generate_pvr_catalog.py`
writes straight to V7. The user's "no tests or load until asked" still applies.

## Movie advance-booking simulation / Ankita PostgreSQL — 2026-10-07

User selected a MOVIE journey workload in the Java loader (POST /load/movie-runs):
browse -> seat map -> adjacent group hold -> checkout(202) -> poll -> optional
cancel; local cap 20/s, 600 s, 12k journeys, 16 concurrent HTTP calls; observer
may run 900 s. Live runs: two 100-journey smokes and one 20/s run the user
STOPPED at 537 journeys. Totals: 482 CONFIRMED, 76 CANCELLED, 104 EXPIRED, no
open payments, all HTTP 2xx. User then said: run no more tests/load until asked.
Resource defaults applied: PostgreSQL 3 GB/2 CPU tuned (shared_buffers 768MB etc.),
APIs 512 MB/1 CPU (MaxRAMPercentage 60, Serial GC), gateway 0.5 CPU, loader 1 CPU.
Ankita-hosted PostgreSQL scripts exist (docs/ANKITA_POSTGRES.md) but were NOT run;
fresh schema via Flyway was selected. Unrelated stacks ticket-booking-java,
url-shortener-java and mcp-gateway-coordinator were stopped (data kept) to free memory.

## Permanent PVR catalog / rolling window — 2026-10-07

User selected permanent movie catalog data for later booking/cancellation
simulation. Read [PVR catalog](docs/PVR_CATALOG_SCHEDULE.md). V6 schema + V7
generated data: 303 real PVR/INOX names in 77 cities (district.in), synthetic
layouts (1,971 screens, 200-500 seats, 2-3 classes), 20-film synthetic slate.
MovieScheduleMaintainer (MOVIE_SCHEDULE_ENABLED, base Compose on, tests/movie
overlay off) fills today..today+3 local days and purges whole past days with
all dependent booking/payment rows (open payments kept; refunds counted in
movie_purge_log). Holds beyond today+3 return 409 SHOW_NOT_YET_OPEN. Regenerate
V7 only via scripts/generate_pvr_catalog.py into a NEW migration (V7 checksum is
applied). Booking/cancellation simulation is the next, separate selection.

## Documentation/status refresh — 2026-10-07

Read [current project status](docs/PROJECT_STATUS.md) before historical notes.
Java virtual-thread loader is delivered at 8f9fd03; generic Node module retained.
At 12:21 IST, APIs/gateway/PostgreSQL and both load-generator groups were stopped
with containers/data retained. PostgreSQL still 1 GiB; Redis phase 3 deferred.
October 6 tests/runtime observations remain dated historical evidence. This request
updates documentation and status only; no service startup or load is selected.
Final Ankita gateway reachability/run remains pending; origin absent. Superseded
teaching-only/unbuilt-client statements below do not undo delivered client work.

## Java virtual-thread generator delivery — 2026-10-06

User selected the earlier URL-shortener Spring Boot generator approach. Read
[Java module](load-tester-java/README.md) and [tutorial](docs/JAVA_LOAD_TESTER_TUTORIAL.md).
Standalone Java 21/Boot 3.5.16 RestClient with virtual threads, own Maven POM and
Docker project sd-book-my-show-java-load-test (384 MiB/0.5 CPU, control 8135 loopback).
Finite HOLD/AVAILABILITY/MIXED generic workloads, stable retry identities,
terminal 409s, seeded jitter, deadlines/concurrency/observer guards, separate
bounded discovery. 14 Java tests and Newman 8 requests/9 assertions passed.
Final local 100-request 10 RPS smoke passed 100 HTTP 201/all SQL violations 0; earlier
Java smoke 100 HTTP 201 and collection 1 hold retained too. Both Java containers stopped
and retained, services/data untouched. Node module remains retained. Final load
on Ankita remains pending; private gateway forwarding not configured here.
Java restart recovery/Node manifest compatibility/movie workflows remain unbuilt.
PostgreSQL 1 GiB unchanged; Redis advisory availability deferred to phase 3.

## Local Docker smoke / resource continuation — 2026-10-06

User explicitly selected this machine for 10 RPS and a separate Docker group.
Read [Docker runbook/result](docs/DOCKER_LOAD_TEST_TUTORIAL.md). Load Compose
project sd-book-my-show-load-test connects only to the existing booking network;
256 MiB/0.5 CPU, fixed 100 fresh holds/10s/one attempt/no faults. Actual 100/100 HTTP 201,
ten buckets of 10, all SQL violations 0, exited 0. Preserve results/fixtures and
retained test containers. PostgreSQL limit is now 1 GiB, live-updated without a
restart; same container/data volume. Base Compose persists 1g/2g total memory+swap.
Redis explicitly deferred to phase 3 for quick availability/high throughput;
500 MiB is a future budget, no Redis service or movie cache/push added. Older
local 20-operation guard remains for the ordinary CLI; this fixed Docker smoke
is a separate explicit selection. Long/final remote load stays on Ankita.

## Current load-test module delivery — 2026-10-06

User authorized the load-test module here and will check it out/run it on Ankita.
Read [runbook](docs/HOLD_LOAD_TEST_TUTORIAL.md) and [verification](docs/HOLD_LOAD_TEST_VERIFICATION.md).
Dependency-free Node >=22 generic comparison is implemented; Maven also requires
Node for one real PostgreSQL client test. Backend/movie behavior stays preserved.
Abhishek hosts actual services/fixtures/scoped ingress/metadata observer; Ankita
alone runs final/sustained business comparison/discovery. User additionally
authorized bounded local loader validation: --local-validation permits loopback
only, <=20 operations/arm, <=8 concurrent and <=30s/arm. Two small local checks
passed with retained fixtures; temporary loopback 8134 ingress closed afterward.
No remote/sustained run, tailnet exposure or Compose change. Default-off finite
busy/committed-loss ingress is fixture/token scoped and heartbeat/STOP bounded.
Retain fixtures/journals/markers; do not reset them to hide ambiguous outcomes.
Shared A/B server admission remains unbuilt; client/ingress caps do not establish it.
Movie workflows/SSE/waiting room/100K remain later. This narrow selection supersedes
older teaching-only notes for the client files; it does not select the entire
broader handover. Origin absent; bundle transfer available, no push claimed.

## Resume entry and durable records

Fresh-session build prompt: [hold retry/admission handover](docs/HANDOVER_HOLD_RETRY_BUILD.md).
Created at user request 2026-10-06 for later invocation. Do not execute it merely
because it is linked here; an explicit user invocation selects its build scope.

Read [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) first for current requirements,
domain boundaries, operation and verified results. Then read
[worklog](docs/WORKLOG.md), [movie tutorial](docs/MOVIE_BOOKING_TUTORIAL.md),
[movie verification](docs/MOVIE_VERIFICATION.md) and [future work](docs/FUTURE_WORK.md).
Update context/worklog when requested or when a delivered change affects the handoff.
Date observations accurately; check current service/remote state before repeating
historical claims. Sections below preserve inherited study context; the current
movie/standalone sections take precedence for this repository.

## Historical movie/planning selection - 2026-10-05

User-selected 2026-10-06: study scenario 2 protections before the hold-retry comparison.
Start with [protection study](docs/SCENARIO_02_PROTECTION_STUDY.md) and the detailed
provider tutorial. This is a documentation/source-reading session, not permission
to run fault/load experiments, switch provider topology or implement the harness.
Record explained/delivered separately from learner mastery or newly executed tests.

Historical teaching-only override: **do not implement yet**. Later loader selections
above supersede it for client work. Read
[hot-show/availability/retry plan](docs/HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md).
Study existing generic scenario 2 protections first; the general hold retry-storm
client comparison is now delivered; movie availability stream/admission remains
unbuilt. Existing
DB-time logical expiry/owner-checked cleanup must not be confused with live push.
Future experiment topology is now selected: Abhishek runs actual services;
Ankita is the business-load generator over Tailscale. Endpoint/ingress/tool/resource
budgets were unresolved in that planning session. Java client budgets now exist;
private remote gateway access remains pending. No new workload is selected by
this documentation/status refresh.
The 33-seat hot-show example does not change current 200-500 screen validation.

User explicitly selected movie booking in this standalone repository, overriding
earlier teaching-only/stop-at-API3 scope for this delivery. Read movie tutorial,
API/ADR/verification/FUTURE_WORK. V5:5-100 screens,200-500 seats,2-3 categories,
atomic 1-10-seat groups, separate show inventory. 95/4.5/0.5 mock plans; one logical
retry per payment; failed payment retains original hold until expiry, fresh key
admits a new payment. Movie tables do not inherit generic outbox/poison semantics.
Optional compose.movie.yml enables fixtures/manual ticks, disables both legacy
and movie loops; base+failover restores scheduling and disables movie controls.
Thousands-user simulation is selected later, with saved plan; do not run it now.
Keep new feature commit timestamps real. Fresh origin URL still awaits the user.

## Standalone repository override - 2026-10-05

This repository is D:/sd-book-my-show, independently initialized at user request.
Read docs/STANDALONE_SETUP.md, docs/THREE_WEEK_SPRINT_PLAN.md and
TIMELINE_PROVENANCE.md. Earlier sections below are inherited source context.
They do not require access to the original workspace to build or test this copy.
No workspace modules are required. Provider stub/config/scripts/docs are included.
Maven artifact is sd-book-my-show; package com.example.booking is preserved.
Compose project sd-book-my-show uses separate retained volumes and host ports
A 8130/B 8131/gateway 8132/provider 8133/PostgreSQL 5553; APIs listen 8105 in containers.
Do not operate on original ticket-booking-java containers or their retained data.
The first 14 commits use disclosed reconstructed sprint dates; verification dates
are real source evidence and must not be rewritten as earlier execution dates.
Future commits use actual dates. Origin awaits the user's new repository URL.
Never delete files/directories or run Maven clean without explicit permission.


## Current API study preference - 2026-10-05

User advanced to API 3: GET /api/events/{id}. Read
[API 3 walkthrough](docs/API_03_GET_EVENT_WALKTHROUGH.md) and its single-request
Postman collection. Stop after this lesson until the learner requests the next.

User requests one API at a time, with manageable IntelliJ code walkthroughs.
Start with POST /api/demo/events; read
[API 1 walkthrough](docs/API_01_CREATE_EVENT_WALKTHROUGH.md). Keep each lesson
focused, offer pause points and one small exercise, and wait for learner readiness
before advancing. This request is teaching/documentation only; existing application
code and infrastructure stay preserved. Do not resume scenario implementation
from a request to review or explain an API.

## Latest scenario 4 delivery - 2026-10-05

User continued the concepts implementation after scenario 3. Read
[outbox tutorial](docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md) and [evidence](docs/VERIFICATION.md).
V4 adds booking delivery_version/outbox/local inbox/receipts/projection. Actual
CONFIRMED/CANCELLED transitions emit once inside the inventory transaction;
no retroactive event backfill or optimistic seat locking. Claim 5s/token commits
before local consume; inbox/effect/projection commit together; ack requires
current token/unexpired lease. Delivery is recoverable with duplicates, no actual
sends/broker/external exactly-once. Monotonic snapshots ignore stale confirmation
after cancellation; no FIFO or delta semantics. DB errors abort batch, other item
errors defer 2s; no outbox exhaustion/quarantine policy is silently inherited.
Default worker requires maintenance+dispatch flags and lifecycle gate. Optional
compose.outbox.yml enables bounded after-consume delay/manual controls with both
loops off; base+failover restores scheduled operation. Gateway excludes replica
outbox controls. Preserve retained events/inbox/receipts/data and other services.
Old writers ignore outbox: drain before rollout. Historical provider 8123 conflicts
with shortener C. Scenarios 1–4 complete; stop;5–6/optimistic seats/retry storms remain
proposals. This section supersedes earlier stop-at-3 notes for the new continuation.

## Current scenario 3 delivery - 2026-10-04

Read [poison tutorial](docs/POISON_JOB_TUTORIAL.md) and [verification](docs/VERIFICATION.md).
Scenario 3 implements per-item processing isolation, durable 3-failure quarantine,
history and keyed maximum 2 lifetime redrives using the original payment UUID.
Provider/DB failures stay separate; UNKNOWN/quarantine remains reconciliation
liability. Redrive does not reset budgets; same-key replay cannot dispatch.
Active token/unexpired lease fences failure recording. Quarantine stays until
terminal apply; late SUCCESS preserves REFUND_REQUIRED and newer seat ownership.
Older workers ignore quarantine: drain before rollout; do not mix old/new workers.
Optional compose.poison.yml enables safe after-accept fixtures and manual recovery
ticks with both schedulers disabled. Default controls/injection off; base+failover
restores scheduled operation. Historical provider 8123 now conflicts with shortener
API C; preserve that service and retained provider-data. Read current topology.
Scenarios 1–3 delivered separately; stop here. 4–6/optimistic seats/retry storms remain
proposals. This latest section supersedes earlier 'scenario 3 next' handover notes.

## Current scenario delivery - 2026-10-04

Scenario 2 provider isolation is implemented and verified. Read
[the provider tutorial](D:/java-projects/ticket-booking-lab/docs/PROVIDER_ISOLATION_TUTORIAL.md)
and [verification](D:/java-projects/ticket-booking-lab/docs/VERIFICATION.md).
Optional provider overlay adds host 8123/container 8121 and retained provider-data;
A 8105/B 8106/gateway 8107/PostgreSQL 5547 data remain. Two HTTP slots per API,
two actual stub processing slots, bounded deadlines/breaker probes, durable 4/10s
retry budget and shared 100/30s backlog admission. Existing checkout keys replay;
exhausted UNKNOWN retains reconciliation, late success requires refunds.
Default simulator and original unlimited-polling collection remain separate.
Stop this delivery. Scenario 3 poison-job isolation is selected next, separately;
4-6, optimistic seat versions and general retry-storm harness remain proposals.
No real payments, production auth/HA/SLO or external exactly-once claim.


## Current scenario study — 2026-10-04

User-directed 2026-10-04: proceed with ticket-booking failure scenarios 1, 2 and
3, one at a time. Each delivery needs a detailed, easy-to-follow study tutorial
and a concise set of 3–5 interview points for the scenario and its subtopics.
Scenario 1 (API failover/graceful restart) is implemented and verified; read
[the tutorial](D:/java-projects/ticket-booking-lab/docs/API_FAILOVER_TUTORIAL.md)
and [evidence](D:/java-projects/ticket-booking-lab/docs/VERIFICATION.md).
Optional overlay: A 8105/B 8106/gateway 8107/PostgreSQL 5547; original data retained.
Local controls default off; no database/host HA or production-auth claim.
Finish this delivery, then stop. Provider isolation (2) and poison-job handling
(3) are selected next, in separate deliveries; 4–6 remain proposals. Optimistic
seat versions and the controlled retry-storm harness remain separate proposals.

User-directed 2026-10-03: this is the primary domain for foundations-first study
of databases, pessimistic/optimistic locking, retries and retry storms. Read
[the foundations tutorial](docs/DISTRIBUTED_SYSTEMS_FOUNDATIONS.md). Use local
downloaded articles/original JavaScript/Python first, then web sources if needed.
Seat-version optimistic locking and a controlled retry-storm harness are proposed,
not existing mechanisms or authorization to change behavior during teaching.

Independent Java 21 / Spring Boot 3.5.16 / Maven project. Read [README](README.md),
[spec](docs/SYSTEM_SPEC.md), [guide](docs/USER_GUIDE.md), [API](docs/API_REFERENCE.md),
[parity](PARITY.md), [verification](docs/VERIFICATION.md) and
[interview guide](docs/INTERVIEW_GUIDE.md) before changes.

Canonical learning context lives at
D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main: load its AGENTS.md,
memory/PROJECT_CONTEXT.md, memory/PROGRESS.md and memory/DECISIONS.md.
Entry: [canonical agents](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/AGENTS.md)
and [current progress](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/memory/PROGRESS.md).

This is a new booking contract informed by related locking/payment examples.
One numbered seat per booking; all inventory mutations lock seat then booking.
Use PostgreSQL clock and conditional transitions. Preserve the active-seat unique
index, scoped hold keys, one payment per booking and immutable provider outcomes.
Provider simulation commits outside inventory transactions; no actual payments.
Late success requires reconciliation, never reassignment of an unavailable seat.

Run mvn -B -ntp verify (real PostgreSQL via Docker/Testcontainers). Compose has
two replicas on loopback 8105/8106 and PostgreSQL 5547 with retained data.
Never delete files/directories or run clean/reset/prune without explicit permission.
Do not alter earlier projects or automatically start another case.
