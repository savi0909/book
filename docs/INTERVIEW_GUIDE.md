# Ticket booking interview practice

Recommended order through the tutorials: [study path](PROJECT_STATUS.md#study-path).

## Movie advance-booking track

Read [the advance-booking tutorial](MOVIE_ADVANCE_BOOKING_TUTORIAL.md). Each part
ends with 3–5 points. Practise these questions out loud; every one has a traced
example in the tutorial.

1. Senior: why are V7's IDs uuid5 values, and what breaks if someone edits V7?
   Describe the safe way to change reference data.
2. Senior: show why the fill is idempotent *without* the advisory lock. What does
   the lock add, and why is a per-batch xact lock not leader election?
3. Senior: delete a show with bookings, payments and receipts in one statement.
   Why do `NO ACTION` foreign keys allow the bookings↔payments cycle, and which
   indexes does the delete need?
4. Senior: a buyer at 02:00 IST is told a show three days out is "not yet open".
   Find the bug (UTC dates) and explain why the database clock decides.
5. Staff: walk through the JIT incident as a method: the wrong hypothesis, the
   evidence (733 ms vs 8.4 ms per no-op batch), the narrow fix (`SET LOCAL`) and the
   stale-jar trap. Then name another place where configured ≠ running.
6. Staff: open versus closed load models; virtual threads versus a semaphore; why a
   409 permits a fresh-key retry but a timeout does not; the 202/200 contract bug.
7. Staff: reconcile the 537-journey run with the database (662 holds = 482 + 76 + 104)
   and say what it does and does not prove.
8. Staff: size a JVM in a 512 MiB / 1 CPU container: heap, GC choice, exit codes
   137 and 143, CPU throttling.
9. Principal: move PostgreSQL to another laptop over Tailscale. Which timeouts see
   network time, what breaks over DERP, how is the port exposed, and where is the
   new single point of failure?

## Load-generation track

[Hold comparison (Node)](HOLD_LOAD_TEST_TUTORIAL.md) and the
[Java loader](JAVA_LOAD_TESTER_TUTORIAL.md) each carry interview points. Core
questions: idempotency protects identity while budgets, deadlines and jitter
protect capacity; a timeout is uncertainty, so keep the key and separate replay 200
from recovery 201; fixed arrivals keep offered load honest; client caps are not
shared server admission; and a local smoke is not a capacity measurement. The
[Docker smoke](DOCKER_LOAD_TEST_TUTORIAL.md) adds resource isolation versus
failure domains.

## Current movie-domain interview track

Read [movie walkthrough](MOVIE_BOOKING_TUTORIAL.md) and [load plan](MOVIE_LOAD_SIMULATION_PLAN.md).
The inherited interview track below remains for generic single-seat studies.

1. Senior: trace sorted locks for a three-category group; prove a 409 leaves no
   partial hold and expired cleanup cannot release replacement ownership.
2. Senior: distinguish same-key discovery, same-payment automatic retry and a
   fresh user payment. Explain the unchanged deadline and late-success refund.
3. Staff: model 20 shows/4000 show-seats versus one hot show. A10000-payment run
   expects 10500 logical calls before infrastructure replay; it does not imply
  10500 simultaneous calls or a measured worker drain rate.
4. Staff: diagnose lock contention, JDBC/servlet/gateway limits, polling and a
   growing payment backlog. Define expected 409 separately from availability errors.
5. Principal: assign inventory/payment/reconciliation ownership; plan movie
   outbox/refund/auth rollout and show partitioning only after evidence. Old API
   versions lack movie routes: migrate schema first, route movie traffic to new
   replicas, and keep generic traffic compatible during the transition.

Scenario 4: [transactional outbox tutorial](TRANSACTIONAL_OUTBOX_TUTORIAL.md) gives
five points/spoken answer plus 3–5 points for atomic source work, recoverable delivery,
inbox/ordering and operations/rollout. Trace the source/consumer/ack commits, explain
why duplicate delivery is expected and distinguish event identity from ordering
version. Demonstrate cancellation-first delivery without projection reactivation;
explain snapshot versus delta semantics and why local receipt uniqueness cannot
prove exactly-once email. Define old-writer drain and historical cutover policy.

Scenario 3: [poison-job tutorial](POISON_JOB_TUTORIAL.md) includes five scenario
points, a short spoken answer and 3–5 points per major subtopic: processing boundaries,
durable scheduling, classification, redrive and operational rollout/capacity. Explain
why quarantined UNKNOWN remains liability, why redrive uses the same UUID and why
old recovery workers must drain before enabling a new quarantine policy. Reproduce
the healthy-batch, duplicate-redrive and late-refund evidence.

## Scenario 1: five points and a 60-second answer

Read [API failover and graceful restart](API_FAILOVER_TUTORIAL.md) for the detailed
study and three key points per subtopic. Lead with these five points:

1. One client endpoint routes to multiple ready APIs; state remains in PostgreSQL.
2. Separate process liveness, readiness and admission; drain rejects new work.
3. A lost response can follow a successful database commit.
4. Same booking/key/payload replays the original intent; the proxy does not retry writes.
5. Test abrupt crash and graceful completion separately; name the remaining proxy/DB failure domains.

Use these as the first answer, then expand on a requested subtopic. The longer
study guide supplies evidence and tradeoffs; it is not a 20-point interview script.

## A 45-minute outline

| Minutes | Explain / produce |
| --- | --- |
| 0–5 | Clarify numbered seats vs general admission, hold duration, groups, pricing, cancellation, checkout and identity assumptions |
| 5–10 | Define no overselling, stale read acceptance, durable hold responses, ambiguous payment outcomes; state sizing assumptions |
| 10–20 | Sketch clients/APIs/inventory DB, event/seat/booking/payment schema and endpoints; choose the authoritative write path |
| 20–30 | Trace two buyers competing for one seat, the transaction order, expiry predicate and scoped idempotency |
| 30–38 | Walk through lost response, provider success after expiry, duplicate callbacks and restart recovery |
| 38–45 | Discuss hot events, admission, optimistic alternatives, sharding, regional ownership and explicit limits |

Practice assumptions: 10,000 numbered seats/event, 100,000 buyers arriving in a
minute (~1,667 attempts/s average; burst traffic and seat popularity are skewed).
Propose a five-minute production hold as a discussion choice; this lab uses 2..120s
to make expiry visible. One seat per booking is implemented; group booking is a
follow-up requiring all-or-nothing sorted locking. No traffic/SLO estimate here is
measured capacity. Ask whether successful cancellation resells a seat and whether
payment may already be accepted when a timeout occurs.

## Data and state diagrams

Use the [spec's architecture/schema/state diagrams](SYSTEM_SPEC.md). Draw payment
as a separate state machine, not an enum hidden inside CONFIRMED. Explain:

```mermaid
sequenceDiagram
  participant Alice
  participant API as API A/B
  participant DB as PostgreSQL
  participant Provider as Local provider
  Alice->>API: Hold seat 1, key h1
  API->>DB: Lock seat, inspect/reclaim, insert HELD
  DB-->>API: Commit
  API-->>Alice: 201 + booking ID/deadline
  Alice->>API: Checkout, key c1
  API->>DB: Lock seat/booking, persist CHECKOUT + intent
  DB-->>API: Commit
  API-->>Alice: 202 accepted
  API->>DB: Claim recoverable payment and commit lease
  API->>Provider: Idempotent acceptance / lookup outside inventory transaction
  Provider-->>API: Success, failure or unresolved
  API->>DB: Lock seat/booking/payment; dedupe event; check expiry
  DB-->>API: CONFIRMED or terminal booking + refund required
```

Booking UUID is the ownership identity; buyer ID is a local label, not authorization.
The composite active-seat unique index constrains cardinality. Hold-key identity is
buyer scoped; checkout identity is bound to one booking. The key must also bind
payload semantics. Timestamps and payment identifiers survive process replacement.

## Locking choices

| Approach | What to explain | What it still needs |
| --- | --- | --- |
| SELECT FOR UPDATE on seat | Serialize a short ownership decision; locks live only to commit | Consistent lock order, expiry after lock wait, timeouts, uniqueness |
| Conditional CAS / version | Read version then update only when ownership/version still matches | Zero-row conflict handling, bounded retries, same expiry and key semantics |
| Serializable transactions | Database detects serialization anomalies | Correct retry/idempotency and handling failure; not external payment atomicity |
| Redis lock | Can reduce contenders arriving at inventory | Durable conditional DB ownership and fencing; lock expiry alone does not prove no overselling |
| In-process synchronized | Coordinates one JVM only | Shared authority for other replicas and restarts |

Pessimistic locking is chosen because contested inventory is the lesson and the
transaction is short. Optimistic checking can be attractive when conflicts are
rare or rejecting retries is cheap. Either can become a hot-seat bottleneck; an
admission queue controls traffic, not durable seat correctness.

## Failure questions and answer checkpoints

| Question | A strong answer connects |
| --- | --- |
| Hold commits but 201 is lost: what does retry do? | Same buyer/key/input discovers the same booking, including expiry. New key is a new request |
| Why not put current time in a partial unique index? | Time changes without a row update; physical active rows must be expired transactionally before reassignment |
| A callback waits behind another transaction and crosses expiry: can it confirm? | Final SQL predicate uses DB time after lock acquisition; refund-required if success is too late |
| A hold expires and Bob reserves; Alice's success arrives: what wins? | Bob's valid ownership stays; Alice's successful payment requires reconciliation |
| What does UNKNOWN mean? | Acceptance may have happened. Query by stable payment identity; do not create a second charge intent blindly |
| Worker dies after provider accepted but before booking update? | Durable intent/receipt, reclaimable lease, deduplicated callback; real provider must support idempotency/query |
| Callback repeated with another event ID? | Receipt outcome remains immutable and payment terminal; delivery may be recorded without repeating transition/refund |
| Can caching improve availability? | It helps metadata/seat browsing. Cached availability cannot confirm a seat during authority outage |
| What about group bookings or multiple regions? | Sorted multi-seat locks/all-or-nothing transaction; one inventory authority per event/partition and an explicit regional failure policy |

Explain compensation precisely: refund required is durable work to resolve, not
proof that money was refunded. This lab marks a local simulated acknowledgment.
A production refund would need its own idempotent operation, retries, reconciliation
and operational alerts. Callback verification and customer ownership must be added
before production exposure.

## A five-minute explanation

“I separate availability display from authoritative reservation. Display reads can
be stale; reservation is a PostgreSQL transaction on an event/seat row. I lock the
seat, recheck its active booking and expire an old hold, then insert a new durable
hold. A unique active-seat index independently prevents two active owners. I use a
buyer-scoped request key so a timeout retry returns the same reservation.

Checkout stores a payment intent and commits before contacting a provider. I never
hold an inventory lock while waiting on a provider response. Payment outcome has
its own lifecycle because a timeout is unresolved rather than a failure. Workers
recover persisted intents and query using the same payment identity.

On success, a callback transaction locks the seat and booking, deduplicates the
event and checks expiry using database time at the conditional update. If the
hold is still valid it confirms. If it expired or was cancelled, it retains that
booking state and records refund required. It cannot take the seat from the next
buyer. Duplicate callbacks do not repeat the transition.

For a popular event, I would bound admission and partition by event, cache browsing
data and measure lock waits, conflicts and unresolved outcomes. Adding API replicas
helps independent seats but cannot remove one seat's serialization bottleneck. The
local implementation proves representative concurrency/recovery paths with two
replicas and real PostgreSQL; HA, real payments and performance require more work.”

This is a reference explanation, not a record of the learner's performance. In a
separate practice-only session, explain it without reading, sketch the trace and
answer two failure questions. Record only answers actually supplied. Review
[verification](VERIFICATION.md) before making claims about tested behavior.

## Scenario 2: provider outage

Use [the provider tutorial](PROVIDER_ISOLATION_TUTORIAL.md) for five concise
interview points, a spoken answer and 3-4 points per subtopic. Explain durable
intent/stable identity; local versus actual remote bulkheads; deadline ambiguity;
one retry owner/durable bounded jitter; breaker generations and one probe;
backlog admission/replay; late-success refunds. Contrast four possible client
calls across A/B with two processing slots at the one stub. Four attempts/10s
bounds automatic dispatch, not resolution time. Count/age admission is an
operational budget, not an adaptive per-hold guarantee. Explicit operator
reconcile bypasses auto budget once per request and must not become a retry loop.
Discuss capacity 2/1.5 operations/s in SLOW as an assumption, host/DB failure domains,
operational ownership and production rollout/metrics limits. Execution evidence
is finite correctness testing; no SLO or learner mastery claim.
