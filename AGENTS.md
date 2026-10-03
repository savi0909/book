# Ticket booking learning lab

## Current API study preference - 2026-10-05

User advanced to API 2: GET /api/events. Read
[API 2 walkthrough](docs/API_02_LIST_EVENTS_WALKTHROUGH.md) and its single-request
Postman collection. Stop after this lesson until the learner requests the next.

User requests one API at a time, with manageable IntelliJ code walkthroughs.
Start with POST /api/demo/events; read
[API 1 walkthrough](docs/API_01_CREATE_EVENT_WALKTHROUGH.md). Keep each lesson
focused, offer pause points and one small exercise, and wait for learner readiness
before advancing. This request is teaching/documentation only; existing application
code and infrastructure stay preserved. Do not resume scenario implementation
from a request to review or explain an API.

## Latest scenario4 delivery - 2026-10-05

User continued the concepts implementation after scenario3. Read
[outbox tutorial](docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md) and [evidence](docs/VERIFICATION.md).
V4 adds booking delivery_version/outbox/local inbox/receipts/projection. Actual
CONFIRMED/CANCELLED transitions emit once inside the inventory transaction;
no retroactive event backfill or optimistic seat locking. Claim5s/token commits
before local consume; inbox/effect/projection commit together; ack requires
current token/unexpired lease. Delivery is recoverable with duplicates, no actual
sends/broker/external exactly-once. Monotonic snapshots ignore stale confirmation
after cancellation; no FIFO or delta semantics. DB errors abort batch, other item
errors defer2s; no outbox exhaustion/quarantine policy is silently inherited.
Default worker requires maintenance+dispatch flags and lifecycle gate. Optional
compose.outbox.yml enables bounded after-consume delay/manual controls with both
loops off; base+failover restores scheduled operation. Gateway excludes replica
outbox controls. Preserve retained events/inbox/receipts/data and other services.
Old writers ignore outbox: drain before rollout. Historical provider8123 conflicts
with shortener C. Scenarios1–4 complete; stop;5–6/optimistic seats/retry storms remain
proposals. This section supersedes earlier stop-at3 notes for the new continuation.

## Current scenario 3 delivery - 2026-10-04

Read [poison tutorial](docs/POISON_JOB_TUTORIAL.md) and [verification](docs/VERIFICATION.md).
Scenario3 implements per-item processing isolation, durable3-failure quarantine,
history and keyed maximum2 lifetime redrives using the original payment UUID.
Provider/DB failures stay separate; UNKNOWN/quarantine remains reconciliation
liability. Redrive does not reset budgets; same-key replay cannot dispatch.
Active token/unexpired lease fences failure recording. Quarantine stays until
terminal apply; late SUCCESS preserves REFUND_REQUIRED and newer seat ownership.
Older workers ignore quarantine: drain before rollout; do not mix old/new workers.
Optional compose.poison.yml enables safe after-accept fixtures and manual recovery
ticks with both schedulers disabled. Default controls/injection off; base+failover
restores scheduled operation. Historical provider8123 now conflicts with shortener
API C; preserve that service and retained provider-data. Read current topology.
Scenarios1–3 delivered separately; stop here.4–6/optimistic seats/retry storms remain
proposals. This latest section supersedes earlier 'scenario3 next' handover notes.

## Current scenario delivery - 2026-10-04

Scenario 2 provider isolation is implemented and verified. Read
[the provider tutorial](D:/java-projects/ticket-booking-lab/docs/PROVIDER_ISOLATION_TUTORIAL.md)
and [verification](D:/java-projects/ticket-booking-lab/docs/VERIFICATION.md).
Optional provider overlay adds host8123/container8121 and retained provider-data;
A8105/B8106/gateway8107/PostgreSQL5547 data remain. Two HTTP slots per API,
two actual stub processing slots, bounded deadlines/breaker probes, durable4/10s
retry budget and shared100/30s backlog admission. Existing checkout keys replay;
exhausted UNKNOWN retains reconciliation, late success requires refunds.
Default simulator and original unlimited-polling collection remain separate.
Stop this delivery. Scenario3 poison-job isolation is selected next, separately;
4-6, optimistic seat versions and general retry-storm harness remain proposals.
No real payments, production auth/HA/SLO or external exactly-once claim.


## Current scenario study — 2026-10-04

User-directed 2026-10-04: proceed with ticket-booking failure scenarios 1, 2 and
3, one at a time. Each delivery needs a detailed, easy-to-follow study tutorial
and a concise set of 3–5 interview points for the scenario and its subtopics.
Scenario 1 (API failover/graceful restart) is implemented and verified; read
[the tutorial](D:/java-projects/ticket-booking-lab/docs/API_FAILOVER_TUTORIAL.md)
and [evidence](D:/java-projects/ticket-booking-lab/docs/VERIFICATION.md).
Optional overlay: A8105/B8106/gateway8107/PostgreSQL5547; original data retained.
Local controls default off; no database/host HA or production-auth claim.
Finish this delivery, then stop. Provider isolation (2) and poison-job handling
(3) are selected next, in separate deliveries; 4–6 remain proposals. Optimistic
seat versions and the controlled retry-storm harness remain separate proposals.

User-directed2026-10-03: this is the primary domain for foundations-first study
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
