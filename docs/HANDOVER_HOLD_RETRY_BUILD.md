# Next-session prompt: controlled hold retries and admission

Continuation2026-10-06: user separately selected the checkoutable load-test
module. Resume [implemented client/runbook](HOLD_LOAD_TEST_TUTORIAL.md) and
[verification](HOLD_LOAD_TEST_VERIFICATION.md). User will run final business
comparison on Ankita; remote evidence pending. Shared server admission unbuilt.
The full copyable prompt below preserves its broader subsequent scope.

Created2026-10-06. This file prepares a later implementation session; creating it
does not start that work now. Copy the prompt below into a fresh session, or send:

> Read D:/sd-book-my-show/docs/HANDOVER_HOLD_RETRY_BUILD.md and carry out its next-session prompt.

## Copyable next-session prompt

Work in **D:/sd-book-my-show**, the standalone Java21/Spring Boot/PostgreSQL movie
and ticket-booking lab. Implement the controlled hold-retry comparison, then
bounded hold-traffic admission as a separately measured change. This invocation
selects implementation for this session and supersedes the prior teaching-only
“do not implement yet” instruction for these deliverables. Do not build all future
features, redesign payments or start a100K workload.

### Load only the necessary context

Read these in order; inspect additional sources when needed rather than loading
every historical tutorial:

1. [AGENTS.md](../AGENTS.md) and [PROJECT_CONTEXT.md](../PROJECT_CONTEXT.md).
2. [Scenario2 protection study](SCENARIO_02_PROTECTION_STUDY.md).
3. [Hot-show availability/retry plan](HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md),
   especially sections5-8.
4. [Simulation plan](MOVIE_LOAD_SIMULATION_PLAN.md), [worklog](WORKLOG.md) latest
   entries and [standalone setup](STANDALONE_SETUP.md).
5. For code changes, relevant spec/API/verification and the movie ADR. Follow
   canonical learning-record instructions in AGENTS.md when updating shared memory.

Check Git status before editing. Inspect actual services, ports and remote state;
the saved stack observations are historical. Resume partial work if the files
already exist instead of rebuilding it.

### Facts that must survive the handover

- Generic scenario2 is already implemented/verified in optional remote-provider
  mode: jittered backoff; four automatic claimed dispatches or ten seconds from
  first claim; local breaker/bounded probes; two HTTP slots/API; independent
  two-slot provider processing; shared checkout backlog100 unresolved/30s oldest.
  Do not reimplement these or claim they protect hold traffic.
- Generic `/api/holds` and movie `/api/movie/holds` have durable key replay,
  seat locking and DB deadlines. Neither currently has general caller retry
  policy/shared hold admission. Replays consume capacity. AdmissionGate only
  manages lifecycle/drain, not throughput.
- Movie V5 is separate: multiplex5-100 screens, screen200-500 seats, two/three
  categories default10/20/70, atomic1-10-seat groups, per-show inventory, mock
  payment plans95/4.5/0.5. Definite failed payment keeps the group until original
  expiry; a fresh payment key creates a new attempt without extending it.
- Existing DB-time logical expiry already permits safe rebooking. Cleanup locks
  historical members and releases only pointers still owned by that booking.
  Late payment success requires refund reconciliation, never seat theft.
- Movie live availability streams, waiting room, movie transition outbox and
  optimistic seat versions are unbuilt.33 seats is a planning example; do not
  change current200-500 screen constraints in this delivery.
- Prior full movie build passed68 tests and bounded runtime/Postman checks.
  Those are historical correctness evidence, not load capacity or tests run now.

### Deliverable A: a finite, reproducible hold-retry comparison

Start with the generic `/api/holds` contract so payment work does not confound
the experiment. Trace BookingService.hold, BookingStore.tx, Errors and HAProxy
before choosing fault injection. Inspect the current Postman/runtime script style.

Build two client modes: **immediate bounded retries** and **bounded exponential
backoff with jitter**. Both use the same initial arrival schedule, maximum total
transmissions, overall deadline, canonical payload and buyer/key identity.
Use separate retained fixtures with equivalent inventory; never make the second
arm a replay-only run against first-arm keys. Preserve ambiguous-operation
discovery.409 SEAT_UNAVAILABLE is a business conflict, not a transient retry signal;
400/key conflicts stop immediately. Keep one in-flight request per logical hold.
After an ambiguous exhaustion, report unresolved rather than inventing failure.

The saved proposal is four total transmissions and a ten-second deadline with
exponential jitter windows capped at two seconds. Review these as configurable
lab defaults; they are not existing endpoint guarantees. Honor server delay floors
and stop when remaining deadline is insufficient. Keep proxy retries disabled;
only the generator/client owns this hold retry loop.

Use explicitly gated, default-off controls or a scoped test ingress for finite
transient busy/response-loss cases. Demonstrate a hold committed despite response
loss and same-key discovery yielding exactly that booking. Do not hold inventory
locks/connections during artificial network delays. Fault controls stay replica/
management-local and outside the Ankita business ingress. Deterministic fixtures
are not production APIs. Baseline uses the existing no-admission behavior.

### Deliverable B: bounded hold-traffic admission, separate comparison

After A is verified, add a default-off study mode for bounding new hold traffic.
Keep the existing comparison unchanged, then compare jittered clients with and
without admission using the same workload. Explain rate versus concurrency caps.

Protect work before seat locks and connection-pool waits where possible with a
bounded process gate. Any shared decision must have a real atomic coordinator
across A/B; two local caps are not one shared cap. Choose the smallest justified
mechanism, document its own DB/network cost and failure domain, and avoid reusing
another project's data/namespace. Do not add a broker or broad infrastructure
merely for the harness. If shared admission needs SQL, use a short bounded admission
operation; do not claim rejection happened before any database access.

Define fresh hold, replay/discovery and other-route budgets separately. Existing
keys need bounded discovery during overload; they must not provide an unlimited
capacity bypass. Rejection must not create an inventory hold, change payload
bindings or extend deadlines. Bound permit lifetime/release across failures and
API restarts. Document whether a replica/control-store failure pauses new traffic
and how existing outcomes remain discoverable. Return clear busy responses/delays
and report them separately from inventory409s.

Verify one actual shared boundary under simultaneous A/B calls, alongside local
limits. Keep generic and movie contracts distinct. If admission is intentionally
generic-only in this delivery, state that plainly rather than suggesting movie
holds are protected. Preserve all movie correctness tests and behavior.

### Experiment topology and execution boundary

**Abhishek runs actual services. Ankita generates business load over Tailscale.**
Abhishek's observer records latency/resource metadata without business-load requests.
Do not send messages, install/run a generator remotely or operate Ankita without
an available authorized execution channel. If none is available, deliver exact
Ankita commands/configuration and record remote execution as pending; continue
implementation and meaningful local correctness checks.

Current movie ports are loopback A8130/B8131/gateway8132/PostgreSQL5553; optional
generic provider8133 is unnecessary for hold-only experiments. A private scoped
gateway ingress is needed before Ankita can reach the service. Inspect existing
Tailscale setup; prepare that route without public exposure or disturbing existing
URL-shortener/Tailscale routes. Preserve database and fault-control isolation.
Identify actual peers/addresses from the environment rather than inventing them.

Build/configure the harness first. Select finite duration, arrival/user/request
caps, resource ceilings and automatic stop conditions before remote execution.
Begin with small correctness fixtures; sustained business-load comparison runs
originate on Ankita. Increase bounded levels only after prior evidence is acceptable.
Do not run100K visitors/users or start other project generators in this delivery.
Record direct/relayed Tailscale path and generator saturation; distinguish Ankita
end-to-end timing from gateway/backend timing. Capture metadata, not bodies.

### Validation and full learning delivery

Add meaningful real-PostgreSQL tests for duplicate concurrent identities, committed
response loss, transient retry behavior, terminal conflicts, deadline/budget stop,
admission limits/release and A/B coordination. Use seeded schedules and repeated
finite comparisons; never promise jitter always improves every latency sample.

Report logical operations, total transmissions/replays, amplification, attempts per
operation, unresolved outcomes, per-second route/status rates, p50/p95/p99,
successful-write latency separately from fast409s, DB pool/lock pressure and
CPU/memory on each host. Keep client-policy comparison separate from admission.
Quiesce bounded work, discover ambiguous keys, then audit no duplicate valid owners,
unchanged deadlines and preserved movie atomic ownership in a consistent SQL view.

Deliver runnable scripts/configuration, appropriate local assertions/Postman entries,
API/spec updates, a detailed tutorial with code/API reading order and exercises,
3-5 interview points, verification with actual results/limits, README links,
AGENTS/CLAUDE context, worklog and canonical progress. Mark each stage implemented,
tested locally or executed remotely accurately. Unexecuted Ankita results must
remain pending, not presented as a completed experiment.

Run task-appropriate Maven verification **without clean**, artifact checks and
whitespace checks. Preserve all unrelated edits/services/data. Never delete files,
directories or volumes, or run reset/prune/clean without explicit permission.
Operate only Compose project sd-book-my-show; the original labs are preserved.
Restore this project's documented normal scheduler/control state after fixtures.

Commit scoped verified changes using actual timestamps. The first14 imported
snapshots have disclosed reconstructed dates; do not backdate new work or evidence.
Push if an authorized origin is configured and verify remote HEAD. No standalone
origin existed at handover; do not reuse the original Java workspace origin or
block useful local work on that missing URL.

Finish the selected deliverables and handoff, then stop. Live availability SSE,
independent expiry scheduling, waiting-room fairness, broader movie provider
protections and100K scale work remain later separately selected deliveries.
