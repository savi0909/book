# Ticket booking interview practice

Scenario3: [poison-job tutorial](POISON_JOB_TUTORIAL.md) includes five scenario
points, a short spoken answer and3–5 points per major subtopic: processing boundaries,
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
