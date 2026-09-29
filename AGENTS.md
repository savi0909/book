# Ticket booking learning lab

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
