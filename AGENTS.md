# Ticket booking learning lab

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
