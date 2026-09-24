# Next-session handover: ticket booking scenario 2

Prepared 2026-10-04. Copy the prompt below into the next session. Scenario 1 is
complete; this prompt selects scenario 2 only. Scenario 3 follows in a separate
session. Recheck repository and runtime state because they may change after handoff.

```text
Continue my distributed-systems study in D:/java-projects/ticket-booking-lab.
Start scenario 2: payment-provider outage without exhausting booking capacity.
Complete this one scenario, its detailed tutorial and verified learning kit,
then stop. Do not start scenario 3 in this session or rebuild scenario 1.

MY LEARNING PREFERENCES
- Use ticket booking as the primary domain; exclude URL shortener from this track.
- Explain thoroughly in plain language, with concrete booking/payment examples.
- Put substantive teaching in a Markdown tutorial under this project's docs/
  folder and link it from README. Keep the chat delivery brief with clickable links.
- Begin with the top 3–5 interview points and a short spoken interview answer.
  For each major subtopic, identify its own top 3–5 points. Put deeper study,
  tradeoffs and exercises below them; avoid a 10–20-point interview checklist.
- Include source-reading order, transaction/request traces, failure diagrams,
  runnable examples, expected observations and IntelliJ/debugging checkpoints.
- Explain correctness first, then capacity, retry amplification, failure domains,
  SLO/measurement limits, operational ownership and rollout reasoning.
- Use my local downloaded articles and original JavaScript/Python examples first.
  Consult official web documentation when clarification or current facts require it.
  Distinguish source designs, implemented mechanisms and executed evidence.

LOAD CONTEXT BEFORE WORK
Java workspace: D:/java-projects.
Read workspace AGENTS.md, then ticket-booking-lab/AGENTS.md and CLAUDE.md.
Read the project's README.md, PARITY.md and these files under docs/:
SYSTEM_SPEC.md, USER_GUIDE.md, API_REFERENCE.md, VERIFICATION.md,
INTERVIEW_GUIDE.md, DISTRIBUTED_SYSTEMS_FOUNDATIONS.md,
FAILURE_SCENARIOS.md (especially section 2), API_FAILOVER_TUTORIAL.md,
and NEXT_SESSION_HANDOVER.md.

Canonical reference tree:
D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main.
Load its AGENTS.md, memory/PROJECT_CONTEXT.md, memory/PROGRESS.md,
memory/DECISIONS.md, docs/learning/PROJECT_DELIVERY_STANDARD.md,
docs/learning/SPRING_BOOT_PARITY.md and docs/learning/cases/Ticket_Booking.md.
This newer handover supersedes the original ticket-booking build prompt for
current scope. The canonical reference tree has no Git metadata; its memory
updates persist locally, separate from the Java workspace's origin push.

Article archive:
D:/AA-SYSTEM-DESIGN-ARCHITECTURE/SDIR-pdf/systemdr-roadmap-sources.
Use _MANIFEST.csv to find relevant articles. It contains 281 HTML articles;
original URLs are in source-canonical-url metadata. It is distinct from the
JS/Python reference repository. Preserve those original implementations.

COMPLETED BASELINE
Java 21 / Spring Boot 3.5.16 / Maven; PostgreSQL seat locks, expiry, durable
request keys, one payment per booking, local provider receipts and recovery.
API A:8105, API B:8106, PostgreSQL:5547. Optional compose.failover.yml adds
HAProxy on loopback8107. Existing data/volumes must remain intact.

Scenario 1 added health-based routing, readiness including DB health, local
admission/drain controls, graceful shutdown and a gated post-commit response
delay. Controls default off; the optional overlay enables them. Proxy retries
are disabled. A committed checkout whose response is lost is recovered through
the other API using the same booking, key and payload. Read the actual source.

Scenario 1 commit b7eb9a5f6b88dc196de115722a853b1497f8d352 was pushed to
origin/main and verified with git ls-remote. A later handover-only commit may
follow it; inspect actual HEAD instead of resetting to that commit.
Verified baseline: 25 Java/PostgreSQL tests; 34 Docker failover checks with
restoration true; failover Postman 17 requests/26 assertions; original Postman
74 requests/109 assertions. All passed. Workspace 21-entry reactor validated.
These are historical results, not evidence for future changes.

At handoff the two APIs, gateway and PostgreSQL were running and accepting work.
Inspect actual state before changing anything. A stopped container named
booking-haproxy-config-check and all exercise fixtures/evidence were retained.
VERIFICATION.md records a corrected wrong-directory harness invocation and its
preserved evidence. The script now rejects wrong directories/unsupported args
before starting an experiment. Run scripts from the selected project directory.

SCENARIO 2: IMPLEMENT AND TEACH
Failure story: a payment provider is slow, unavailable, or accepts a payment
but loses its response. Healthy browse/hold operations should retain capacity;
accepted payment intents must remain discoverable and recoverable.

Inspect LocalProvider, PaymentProcessor, Maintenance, BookingService, current
SQL migrations, failover admission and tests before choosing the implementation.
The current provider simulator shares PostgreSQL. Stopping that database does
not demonstrate an independent provider outage. Introduce a bounded, separately
controllable local provider stub/dependency; no real payments or external sends.

Study these five connected mechanisms:
1. Bulkhead: bounded provider concurrency and queue/admission behavior. State
   whether each limit is per process or shared across both APIs.
2. Deadlines: per-attempt and overall budgets. A caller timeout does not prove
   remote work stopped; avoid freeing capacity while unbounded work continues.
3. Circuit breaker: CLOSED/OPEN/HALF_OPEN, bounded probes and recovery behavior.
4. Retry control: one retry owner, bounded attempts/budget, backoff with jitter,
   durable deferral and stable payment identity. Avoid layered retry amplification.
5. Graceful degradation/reconciliation: UNKNOWN for ambiguous acceptance,
   backlog age/recovery metrics and a clear checkout-admission policy when the
   backlog exceeds useful hold lifetime or the operational budget.

Preserve short inventory transactions; provider calls must not hold seat locks
or inventory DB connections. Preserve seat-then-booking lock order, SQL-clock
expiry, active-seat uniqueness, scoped idempotency keys and immutable outcomes.
Late success must retain refund reconciliation and must never steal a seat
from a newer valid booking. No exactly-once network/payment claim.

Original local reference: under the canonical tree, inspect
Graceful_Service_Degradation/graceful-degradation-demo/src/circuit-breaker.js.
Its Promise timeout does not cancel the operation and it lacks bounded half-open
admission. Borrow the teaching model, not those correctness gaps. Discover other
relevant retry/bulkhead references through the archive and reference tree.

PROVE AND DELIVER
Use finite deterministic experiments for slow/unavailable provider, ambiguous
acceptance, exhausted retry budget and recovery. Assert concurrency bounds,
actual in-flight work after timeouts, continued browse/hold progress, stable
payment identity, bounded probes/attempts and durable reconciliation. Record
backlog age and drain observations; do not convert local timings into an SLO.
Preserve and run appropriate scenario 1/booking regressions after changes.

Deliver incrementally: implementation, meaningful real-infrastructure tests,
local Compose/config as needed, tutorial, API/user/spec/interview/parity docs,
Postman collection/environment with assertions, verification and agent/context
updates. Use additive migrations if needed; do not rewrite applied history.
Update canonical case/progress/decisions to record what actually passed and
what remains. Tutorial authorship/test success does not imply learner mastery.

WORKFLOW AND BOUNDARIES
Work autonomously within this selected scope; do not ask me to reconfirm it.
Check Git status first. Preexisting unrelated .idea/compiler.xml,
.idea/encodings.xml, staged .idea/jpa.xml and .idea/misc.xml edits were present
at handoff; preserve them and do not include them in this task's commit.
Never delete files/directories or run Maven clean, destructive reset or Docker
prune without my explicit permission. Preserve other learning projects/stacks.
After task-appropriate validation, commit only task changes, push to origin,
and verify remote HEAD; do not claim an unverified push succeeded.
Provide concise progress updates; incorporate additional notes into the active
task. A status question does not cancel the work. Stop if I explicitly ask.

Scenario 3 (poison-job isolation/quarantine/redrive) is selected for the following
separate delivery. Scenarios 4–6, optimistic seat-version comparison and a general
retry-storm harness remain proposals. Do not silently expand into them.
```

Start by reviewing [scenario 2's failure story](FAILURE_SCENARIOS.md#2-provider-outage-without-exhausting-booking-capacity).
The completed lesson is [scenario 1](API_FAILOVER_TUTORIAL.md); its exact evidence
is in [VERIFICATION](VERIFICATION.md).
