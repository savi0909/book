# Next-session handover: ticket booking scenario 3

Prepared 2026-10-04 after scenario 2. This selects scenario 3 only for the next
separate delivery. Scenario 2 is complete; stop the current session after its kit.

```text
Continue D:/java-projects/ticket-booking-lab with scenario 3: poison-job isolation,
quarantine and controlled redrive. Finish this one scenario and its learning kit,
then stop. Do not rebuild scenarios 1 or 2 or start scenarios 4-6.

Read D:/java-projects/AGENTS.md and project AGENTS.md/CLAUDE.md. Load README,
PARITY and docs/SYSTEM_SPEC.md, USER_GUIDE.md, API_REFERENCE.md, INTERVIEW_GUIDE.md,
VERIFICATION.md, FAILURE_SCENARIOS.md, API_FAILOVER_TUTORIAL.md,
PROVIDER_ISOLATION_TUTORIAL.md and DISTRIBUTED_SYSTEMS_FOUNDATIONS.md.
Read the canonical tree at
D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main:
AGENTS.md, memory/PROJECT_CONTEXT.md, memory/PROGRESS.md, memory/DECISIONS.md,
docs/learning/PROJECT_DELIVERY_STANDARD.md, SPRING_BOOT_PARITY.md and
cases/Ticket_Booking.md. Canonical memory has no Git metadata and persists locally.

Learning preferences: detailed plain-language Markdown tutorial under docs/,
README link, 3-5 scenario interview points and short spoken answer; 3-5 points
for each major subtopic, then deep explanations. Include source-reading order,
transaction/request traces, diagrams, runnable examples and observations,
IntelliJ checkpoints, tradeoffs/exercises, capacity/failure domains/SLO limits,
operational ownership and rollout. Use downloaded local articles and original
JS/Python source first. Archive:
D:/AA-SYSTEM-DESIGN-ARCHITECTURE/SDIR-pdf/systemdr-roadmap-sources/_MANIFEST.csv.
Keep source designs, implemented mechanisms and executed evidence distinct.

Baseline: Java21/Spring Boot3.5.16/Maven/PostgreSQL; seat-then-booking locks,
SQL-clock expiry, active-seat uniqueness, scoped durable request keys, one
payment per booking, immutable provider outcomes and late-success refunds.
Scenario1 adds optional HAProxy8107, readiness/DB health, admission/drain,
graceful shutdown and gated post-commit response delay; proxy retries disabled.
Scenario2 adds optional independent Java provider on host8123/container8121,
retained provider-data journal, two HTTP slots per API/no waiting queue and two
actual provider processing slots across both APIs. Faults NORMAL/SLOW/UNAVAILABLE/
LOSS, bounded1500ms surviving remote work. Connect200ms/request400ms;
three-failure breaker/two-second cooldown/one half-open probe per API/generation
fencing. V2 adds durable retry start/exhaustion/last error. Automatic dispatch
budget4 or10s, capped exponential jitter and durable nextAt. Attempts include
local rejections. Explicit demo reconcile bypasses the automatic budget once per
request but respects active leases and never resets identity/budget. No provider
HTTP under inventory transactions. Count>=100 or oldest unresolved>=30s pauses
new remote-mode checkout; existing key/input replay precedes admission.
Default simulator remains available without PROVIDER_URL. Original booking
collection expects unlimited polling; use the default topology for that suite.
Scenario2 collection/harness use the provider overlay and run sequentially.

Topology: A8105/B8106/gateway8107/PostgreSQL5547/provider8123. Existing other
project coordinators own8121/8122; never disturb them. Use:
docker compose -f compose.yml -f compose.failover.yml -f compose.provider.yml ...
Check actual state before work. Preserve booking-data/provider-data and fixtures.
Read VERIFICATION for current results, corrections and exact evidence.

Scenario3 failure: an unexpected deterministic per-item exception can escape
recoverBatch's loop to Maintenance.tick, skipping later items; expiry and recovery
also share a catch. ProviderBoundary.Unavailable is already handled per payment.
Do not label a global database/provider outage as a malformed poison job. Existing
retryExhausted describes provider dispatch budget, not a poison quarantine.

Inspect PaymentProcessor, Maintenance, BookingStore, BookingService, LocalProvider,
ProviderBoundary, both migrations and tests. Load the original poison-job source
through the canonical index/tree and relevant archived article. Add explicitly
gated deterministic fixtures; per-item failure classification/isolation,
persistent quarantine reason/attempt history, bounded redrive using the original
payment UUID, and fairness/continued healthy work. Use additive migrations, not
rewritten history. Keep inventory transactions short and lock order intact.
Uncertain acceptance still needs reconciliation after quarantine; stopping work
never manufactures FAILURE or a refund. Success after expiry cannot steal a seat.

Prove a failing first candidate does not block several healthy payments, attempts
are bounded, quarantine visible and deliberate redrive safe after fixing cause.
Verify restart/cross-replica ownership, duplicate redrive, shared outage behavior,
backlog age/admission interactions and no loss of existing identity/refund semantics.
Run meaningful real-PostgreSQL tests and appropriate scenario1/2 regressions.
Deliver code/config, tutorial, spec/API/user/interview/parity docs, Postman with
assertions, verification/evidence, agent context and canonical case/progress/decisions.
Tests and tutorial delivery do not establish learner mastery.

Work autonomously. Preserve preexisting .idea/compiler.xml, encodings.xml,
staged jpa.xml and misc.xml; check current Git status. Never delete files/directories
or run Maven clean, reset or Docker prune without explicit permission. No public
deployment or real payments. After successful validation commit only task changes,
push origin and verify remote HEAD. Report limitations honestly. Stop after
scenario3; optimistic seat versions/general retry-storm harness/connection storms
and scenarios4-6 remain separate proposals.
```

Start with [scenario 3](FAILURE_SCENARIOS.md#3-poison-job-isolation-and-controlled-redrive).
Completed study: [scenario 2](PROVIDER_ISOLATION_TUTORIAL.md).
