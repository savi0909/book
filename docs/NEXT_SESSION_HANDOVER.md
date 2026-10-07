# Next-session handover: standalone Book My Show

Updated2026-10-07. Resume `D:/sd-book-my-show`, not the original ticket workspace.
Read [current project status](PROJECT_STATUS.md), [context](../PROJECT_CONTEXT.md),
[agent instructions](../AGENTS.md) and [worklog](WORKLOG.md) first.

```text
Resume D:/sd-book-my-show from its current project status and Git state.
Java virtual-thread load tester is delivered at implementation commit8f9fd03:
load-tester-java is a standalone Boot3.5.16/Java21 application using RestClient,
fixed initial arrivals and finite generic hold/read/mixed workloads. Its own
Docker group publishes management8135 on loopback.14 tests and Newman8 requests/
9 assertions passed; two local100-request10 RPS smokes returned100201, SQL
violations0. Preserve the retained Node module and all fixtures/recovery markers.

Read docs/JAVA_LOAD_TESTER_TUTORIAL.md and load-tester-java/docs/VERIFICATION.md.
Current status inspection2026-10-07 found APIs/gateway/PostgreSQL and Java/Node
generators stopped, containers/data retained. PostgreSQL remains1 GiB.
Final business execution belongs on Ankita, actual services on Abhishek; private
gateway reachability/run is pending. No Tailscale configuration was changed.
Redis is deferred phase3 (proposed500 MiB) for advisory availability; SQL retains
seat authority. Shared hold admission/movie load/SSE/waiting room/100K and Java
automatic restart discovery are not implemented by the generator delivery.

This handover records state; it does not automatically start services, load,
faults or a new feature. Follow the user's next explicit selection. Documentation
and study requests stay within their scope. Commit validated task changes; push
only to this standalone repository's origin when configured and verify its SHA.
Origin currently absent; verified complete-history bundles support transfer.
Preserve unrelated edits and all data; never delete files/directories or run
Maven clean/reset/prune without explicit permission.
```

## Historical inherited handovers

The original ticket-workspace review/implementation prompts below are retained as
dated source history. Their original addresses, working tree and runtime state
do not describe this standalone repository's current status.

## Historical scenario4 review — 2026-10-05

Latest user continuation2026-10-05 selected and delivered scenario4. Read
[transactional outbox tutorial](TRANSACTIONAL_OUTBOX_TUTORIAL.md) and current
[verification](VERIFICATION.md). The following section supersedes older stop-at3
and historical implementation prompts retained below.

```text
Review completed ticket-booking-lab scenarios1–4, one detailed study at a time.
Load workspace/project AGENTS plus canonical memory and current spec/API/guide/
parity/verification/interview docs. Focus on docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md:
V4 source transaction stores CONFIRMED/CANCELLED snapshots; source versions are
event sequence, not optimistic seats. Outbox claim commits before local sink;
inbox/receipt/projection commit together; separate token/unexpired-lease ack.
Two actual API crashes prove committed delivery-work recovery and duplicate-safe
local effects. Old confirmation cannot overwrite observed cancellation2. No
historical backfill, real sends/broker/auth/HA/external exactly-once claim.
Default base+failover scheduled topology restored; optional compose.outbox.yml
disables both loops/enables bounded local fault controls. Preserve all volumes,
event/inbox/receipt fixtures and other stacks; provider8123 conflicts with shortener C.
Explain the three commits, inbox/effect atomicity, source/consumer failure domains,
snapshot-vs-delta ordering, old-writer drain and operational count/age ownership.
Scenarios1–4 complete; stop. Refund saga5, database HA6, optimistic seats/general
retry storms remain separate proposals; do not implement automatically during review.
Requested substantive teaching goes in docs/, README-linked; validated changes
are committed/pushed with remote verification. Preserve .idea edits/staged jpa.xml;
never delete files/directories, run Maven clean or Docker prune without permission.
```

## Historical scenario3 review handover

Latest delivery2026-10-04: scenario3 is implemented and verified. Read
[poison tutorial](POISON_JOB_TUTORIAL.md) and [verification](VERIFICATION.md).
Scenarios1–3 are complete; stop after this kit. Do not automatically start4–6,
optimistic seat versions, the general retry-storm harness or connection storms.

```text
Review D:/java-projects/ticket-booking-lab's completed scenarios1–3.
Load workspace/project AGENTS and current docs/VERIFICATION.md, SYSTEM_SPEC.md,
API_REFERENCE.md, USER_GUIDE.md, PARITY.md and all three scenario tutorials.
Load canonical memory at D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main.
Scenario3: additive V3, per-claimed-item catches,3 failures quarantine UNKNOWN,
durable history, keyed maximum2 lifetime redrives, no budget resets. Provider/DB
failure stays separate. Same-key replay discovers state without another attempt;
crash after claim consumes key; wait for lease then choose a new key if available.
Late success requires REFUND_REQUIRED and cannot steal a newer owner's seat.
Default base+failover scheduled simulator topology restored; poison controls off.
For deterministic study use base+failover+compose.poison.yml: both schedulers off,
explicit recovery tick, safely gated after-accept fixture. Run tutorial/Postman
sequentially. Provider overlay's historical8123 conflicts with shortener API C;
preserve other project services and retained booking/provider volumes.
Explain-back questions: why UNKNOWN survives quarantine; why provider budget is
separate; how same-key redrive is serialized across replicas; what happens when
acceptance precedes poison failure; why old workers must drain before rollout.
Teaching/review-only work does not authorize new application scenarios. Keep
substantive requested teaching in docs/, README-linked. Commit/push validated
requested changes, preserve .idea edits, never delete files or run clean/prune.
```

The following prompt is retained as **historical pre-implementation context**.
It does not select scenario3 again or supersede the completed delivery above.

## Historical scenario3 implementation handover

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
