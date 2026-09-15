# Ticket booking learning lab

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
