# System specification

Client-study extension2026-10-06: [hold-load module](HOLD_LOAD_TEST_TUTORIAL.md)
uses unchanged generic holds with bounded retry/discovery/distinct fixtures.
Process/ingress caps do not implement shared admission or movie protection.
[Local evidence / remote boundary](HOLD_LOAD_TEST_VERIFICATION.md).

Current movie specification: [movie tutorial](MOVIE_BOOKING_TUTORIAL.md) and
[ADR](ADR_001_MOVIE_GROUP_BOOKING.md). The document below covers the preserved
generic single-seat domain; its provider/outbox guarantees do not automatically
apply to movie tables.

## Scenario4 extension — transactional outbox and local inbox

V4 adds delivery_version to bookings and retained booking_outbox, notification_inbox,
local_notification_receipts and booking_delivery_projection. Actual CONFIRMED/CANCELLED
transitions increment sequence and insert one immutable schemaVersion1 snapshot in
the same inventory transaction as state/audit. Replays emit no duplicate source
event. Late success without confirmation emits none. Pre-V4 history is unbackfilled;
delivery_version0 is the cutover baseline, not optimistic seat locking.

Dispatcher selects20 due undelivered events, conditionally claims5s/random token,
commits before consume and acknowledges separately with current token/unexpired
lease. Sink locks per-booking projection and atomically commits inbox plus advancing
snapshot/receipt. Duplicate event ID increments deliveries without another effect.
Older snapshot produces STALE_IGNORED inbox only; version comparison prevents old
confirmation overriding already observed cancellation. Snapshot skips are supported;
no FIFO/delta-processing or externally ordered-send claim. Source/consumer/ack are
separate commits but share PostgreSQL availability.

DB/transaction failure aborts dispatch batch and retains claim until lease expiry;
other RuntimeException defers item2s and permits later items. Outbox retries remain
enabled indefinitely with due/lease bounds; payment poison3-failure quarantine is
not applied implicitly. No whole-workflow delivery deadline or dead-letter policy.
Scheduler ticks500ms after completion, gated by maintenance+outbox flags and lifecycle
admission; default scheduler thread is shared with maintenance. Optional outbox
overlay disables both loops and enables bounded0..10000ms after-consume delay and
manual controls; defaults off/no delay. Gateway excludes replica mutation controls.
Diagnostics expose per-booking source/inbox/receipt/projection plus pending count/age.
Old writers ignore outbox; drain them before source rollout. No historical backfill,
real sends/broker, HA/auth/SLO or external exactly-once claim. See
[tutorial](TRANSACTIONAL_OUTBOX_TUTORIAL.md) and [verification](VERIFICATION.md).

## Scenario 3 extension — poison-job isolation

V3 adds durable itemFailures(0..3), quarantinedAt/reason, redriveCount(0..2),
optional poison fixture and recovery_history. Claims, caught item failures,
quarantine, redrive keys and applied outcomes persist. Unexpected RuntimeException
after a successful claim defers UNKNOWN1s and quarantines on the third recorded
failure. Failure recording requires the current token and an unexpired lease.
Automatic candidates exclude quarantine, order nextAt/UUID and retain20-item
batches. Provider failures retain scenario2 policy; DataAccessException and
TransactionException abort the batch without item quarantine. Maintenance expiry
and payment phases now have independent catches within lifecycle admission.

Quarantine does not mean payment FAILURE and still contributes to unresolved
count/age checkout admission. Ordinary reconcile rejects quarantine. A gated keyed
redrive locks the payment row and records its key/cap admission in the same
transaction as the original-ID lease claim. At most2 lifetime extra dispatches;
same-key replay does not dispatch. Active leases reject; no retry counters/budgets
reset. Quarantine remains until terminal application, including when redrive defers.
A crash after claim still consumes that key; investigate, wait for lease expiry
and use another deliberate key if available. Verified callbacks can resolve outcomes
after redrive exhaustion. Inventory transitions retain seat→booking→payment locking
and late-success refund obligations. No inventory transaction spans provider work.

The optional compose.poison.yml enables deterministic controls and disables both
automatic maintenance loops. Default controls/fixture injection are off; diagnostics
remain available. Fault occurs after simulator acceptance, before callback apply.
Existing data/migrations retained. Older workers ignore quarantine: drain them
before rollout; mixed-version automatic recovery is unsafe. No process-crash
containment or starvation-freedom/SLO claim. See [tutorial](POISON_JOB_TUTORIAL.md).

## Scenario 2 extension — independent provider isolation

The optional [provider overlay](../compose.provider.yml) adds a Java stub on
loopback8123 with a separate retained journal, independent of booking PostgreSQL.
Default behavior remains the original local simulator described below. Calls
run outside inventory transactions; valid replies commit a local receipt
observation separately. Callback application never performs provider HTTP.
Two client-call slots per JVM/no waiting queue; two actual processing slots at
one stub across both APIs. Stub dispatch executor:8 threads/queue32, backlog32.
HTTP connect200ms/request400ms; remote SLOW/LOSS work remains bounded1500ms.
Three dependency failures open a per-JVM breaker for2s; one half-open probe;
generation fences stale completion. Readiness continues to include booking DB.

V2 adds retryStartedAt/retryExhausted/lastError without altering V1. Automatic
recovery admits at most4 claimed dispatches or10s since first claim, with capped
exponential bounded jitter100..500/1000/2000ms and durable nextAt. Claims rejected
by local breaker/bulkhead consume the dispatch budget too. Budget exhausted
UNKNOWN remains discoverable; explicit operator reconcile can make one attempt
without resetting budget. The deadline bounds automatic dispatch admission,
not end-to-end outcome completion. Leases remain5s/token checked.

Remote-mode new checkout uses a shared SQL advisory admission lock and rejects
at100 unresolved intents or oldest age>=30s; replay is checked first. Browse/hold
remain available. This operational age rule is not an adaptive hold-TTL policy.
Late success preserves REFUND_REQUIRED and the newer seat owner. Provider counters
are local/transient; backlog age/count/retry metadata are shared/persistent.
See [detailed tutorial](PROVIDER_ISOLATION_TUTORIAL.md) and current verification.
No real payments, exactly-once network guarantee, provider fleet quota or host HA.

## Scenario 1 extension — API failover and drain

Optional `compose.failover.yml` adds a digest-pinned HAProxy on loopback 8107 over
API A/B. Probes use readiness including database health; zero proxy retries avoid
implicit write replay. A synchronized local admission gate closes new business
requests and maintenance ticks while accepted work finishes. Drain sets Spring
REFUSING_TRAFFIC; restart resets transient drain state. This is lifecycle admission,
not a throughput quota, circuit breaker or fair waiting room.

Gated local controls and a checkout response-delay header expose a deterministic
post-commit/pre-response window. Delay never holds the checkout transaction.
The server uses graceful shutdown with 15s per Spring phase; the overlay gives
containers 20s stop grace. SIGKILL bypasses this path. Existing SQL identity/lease
contracts recover uncertain work. No schema or payment-provider change is needed.
See [the tutorial](API_FAILOVER_TUTORIAL.md), [API](API_REFERENCE.md) and current
[evidence](VERIFICATION.md). A single proxy/DB/host remains; no DB/zone HA claim.

## Problem and bounded requirements

Many buyers want the same seat. A successful reservation response must have a durable
owner, while a payment response may arrive after the seat is available to somebody
else. Serve event metadata/seat availability, create temporary holds, accept one
checkout intent per booking, confirm through provider success, cancel, expire,
retrieve state/audit and expose reconciliation. One numbered seat per booking;
multiple independent events use the same model, 1..200 seats each. Group bookings,
seat maps, real authentication, dynamic pricing, fees and real payments are excluded.

Each seat costs 5000 minor units (INR 50.00), fixed in event metadata. Checkout has
no caller-supplied amount and no card field. This is an inventory/concurrency lesson,
not a financial ledger. Demo identities are labels; knowing a booking UUID permits
access and cancellation in this local API.

## Architecture and schema

```mermaid
flowchart LR
  Buyer[Demo buyer / Postman] --> A[API A :8105]
  Buyer --> B[API B :8106]
  A --> DB[(PostgreSQL :5547)]
  B --> DB
  A --> PA[Local provider simulator]
  B --> PB[Local provider simulator]
  PA --> DB
  PB --> DB
```

Both replicas run the same executable and a bounded maintenance loop. The provider
simulator stores receipts in the same local PostgreSQL instance but commits them
separately from inventory. This exposes the acceptance/application gap; it does
not simulate an independently available provider or a distributed transaction.

```mermaid
erDiagram
  EVENTS ||--|{ SEATS : contains
  SEATS ||--o{ BOOKINGS : historical
  BOOKINGS ||--o| PAYMENTS : checkout
  PAYMENTS ||--o| PROVIDER_RECEIPTS : acceptance
  PAYMENTS ||--o{ PROVIDER_EVENTS : deliveries
  BOOKINGS ||--|{ BOOKING_AUDIT : changes
```

The [migration](../src/main/resources/db/migration/V1__booking.sql) provides seat
composite primary/foreign keys, unique buyer/hold-key pairs, unique payment booking
IDs, a partial unique active-seat index, valid-state checks and immutable receipt
identity. Constraints establish cardinality; application transitions establish
permitted lifecycle behavior. Direct administrative SQL can still violate business
semantics and is outside the API guarantee.

## Invariants and clock

1. A seat has at most one physical booking in HELD, CHECKOUT or CONFIRMED. HELD and
   CHECKOUT are logically valid only while `expires_at > PostgreSQL clock_timestamp()`.
2. Every inventory mutation locks the seat row before the booking row, then the
   payment row if needed. READ COMMITTED plus this common locking order serializes
   competing decisions; the partial unique index is an independent no-oversell backstop.
3. Confirmation requires provider SUCCESS, the booking's own payment and a conditional
   SQL transition with expiry checked after obtaining locks. Expiry is inclusive at
   equality. The confirmation decision linearizes at this update, not at HTTP receipt
   or commit time. A confirmed booking no longer expires as a hold.
4. A hold key is scoped to buyer ID and permanently binds event, seat and TTL to one
   booking. Same key/input returns the current state of that booking, never a new hold.
   Changed input conflicts. Checkout permits one durable intent per booking and requires
   its original key/scenario/delay on retries, even after cancellation or expiry.
5. Provider outcome is immutable. Duplicate event IDs bind payment and outcome;
   conflicting reuse rejects. Different event IDs for a terminal outcome add delivery
   records without repeating confirmation/refund audit actions.
6. Success on an expired/cancelled booking records payment SUCCESS and REFUND_REQUIRED.
   It never assigns the seat again. A local refund acknowledgment records
   REFUNDED_SIMULATED; no external refund occurs.

Availability reads use `statement_timestamp()` to classify a single-query snapshot.
Application wall clocks and browser timers are advisory only. A forward database clock
jump can expire holds early; a backward jump can extend them. Monotonic distributed
time and failover clock behavior are not modeled.

## Booking and payment state

```mermaid
stateDiagram-v2
  [*] --> HELD
  HELD --> CHECKOUT: durable intent
  CHECKOUT --> CONFIRMED: success before expiry
  CHECKOUT --> PAYMENT_FAILED: failure while active
  HELD --> EXPIRED: deadline reached
  CHECKOUT --> EXPIRED: deadline reached
  HELD --> CANCELLED: cancel
  CHECKOUT --> CANCELLED: cancel
  CONFIRMED --> CANCELLED: cancel and require refund
```

Payment independently moves PENDING -> UNKNOWN -> SUCCESS/FAILURE, or directly from
PENDING to a terminal outcome. UNKNOWN deliberately means the application cannot yet
conclude the result. The UNKNOWN simulator scenario accepts SUCCESS durably but hides
its first response. Delay can also leave an outcome unresolved until `ready_at`.
Refund reconciliation is a separate field, not a booking state. A payment FAILURE
arriving after expiry/cancellation retains that booking state.

## One reservation trace and transaction scope

Buyer Alice submits hold seat 1 with key h1. Transaction H takes a PostgreSQL transaction
advisory lock on the hash of buyer/key, checks a prior request, locks the seat, expires
any old physical hold and inserts HELD with a database-generated deadline plus audit.
The advisory lock only serializes request identity; seat correctness depends on the
row lock and active-seat index. Hash collisions merely add contention. H commits before
201. Bob's transaction then observes HELD and gets 409. A new key after timeout would
represent a new logical request; retry h1 instead to discover the committed booking.

Alice starts checkout. Transaction C locks seat/booking, conditionally changes HELD to
CHECKOUT and inserts PENDING payment plus audit. It commits before 202; 202 means durable
intent, not payment success. The maintenance loop claims a payment in a short transaction
using a random token and five-second lease. Claim commits; provider acceptance commits
in a separate transaction. No inventory lock spans that interaction. A callback transaction
then locks seat -> booking -> payment, checks receipt/readiness and event identity, and
updates payment/booking/audit atomically. Confirmation's final SQL expiry predicate can
fail even after the earlier expiry check; that path records EXPIRED plus refund required.

If an API dies after the claim or receipt commit, another replica reclaims the intent
after lease expiry and uses payment UUID to find the same receipt. Stale tokens cannot
apply a result; manual callback completion also makes old workers harmless. No external
exactly-once claim follows from this local identity model.

The loop runs every 500ms, expires up to 50 bookings and processes up to 20 candidate
payments. Conditional lease claims coordinate replicas. Unresolved responses schedule
the next lookup at the later of provider ready time and now+3s. Manual reconciliation
ignores next-at, respects active leases and receipt readiness, and returns current state.
Workers are persistent-state driven; no in-memory timer is required for correctness.
The loop retries failures in later ticks; there is no fairness or maximum recovery-lag SLO.

GET booking and hold replay also materialize expiry. New holds reclaim expired rows
under the seat lock. Seat reads classify expiry without writing. Rejected writes roll
back all transaction changes, including an attempted expiry; lazy reads, sweeper and
successful seat reassignment still converge physical state. Logical expiry is immediate
according to the database clock regardless of sweeper lag.

## Alternatives and scaling

| Choice | Benefit | Limit / alternative |
| --- | --- | --- |
| Short pessimistic seat lock | Explicit serialization under high contention | Hot seat queues; lock timeout yields 503. Optimistic version/CAS can reject quickly but still needs retry and expiry/uniqueness checks |
| PostgreSQL inventory authority | Atomic durable ownership across replicas | Primary outage rejects writes. Redis locks require durable conditional ownership underneath |
| Persistent lease polling | Recoverable work without another service | Poll overhead and shared DB; at higher scale use an outbox plus broker, retaining idempotent consumer transitions |
| Single seat per transaction | Small lock scope and clear invariant | Groups need sorted seat locks and all-or-nothing reservation, not repeated independent requests |
| Retained identity/audit records | Ambiguous retries remain discoverable | Storage grows; production retention requires a defined idempotency window and archival policy |

Interview sizing assumption, not a benchmark: an event might receive 100,000 buyers
in a minute for 10,000 seats (~1,667 attempts/s on average, with larger bursts). Hot-seat
contention is skewed; adding replicas cannot increase a single seat's serialized decision
rate. Introduce event-based partitioning, bounded admission/waiting rooms, jittered retries
and read caches for metadata/approximate availability. Cache snapshots cannot authorize a
hold. Keep inventory for an event in one authoritative region; replica lag must not
confirm ownership. Querying aggregate stats uses separate reads and is advisory.

Local limits: 8 JDBC connections and 32 servlet threads per JVM, 2s row-lock timeout,
3s statement timeout, 5s JDBC socket timeout, 3s connect/pool timeout, 100-page maximum
and 200 seats/event. These bound some waits, not end-to-end latency under every overload.
No global admission, per-buyer quotas or stored-record cap exists. The two nodes share
a single primary and provider database. Database/host HA, power-loss recovery, independent provider
outages, callback authentication, real refund idempotency and sustained throughput
require separate design/verification.

## Source and technical evidence

Related source mechanisms and defects are documented in [PARITY](../PARITY.md).
Executed claims are in [VERIFICATION](VERIFICATION.md); this specification separates
design assumptions from observed results. Java 21 compatibility was checked against
[Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
The row-lock behavior follows [PostgreSQL locking documentation](https://www.postgresql.org/docs/16/explicit-locking.html).
