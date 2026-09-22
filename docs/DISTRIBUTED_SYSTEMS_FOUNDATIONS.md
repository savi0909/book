# Learn distributed systems through ticket booking

Requested and refined: 2026-10-03. Primary learning project: `ticket-booking-lab`.

For focused next additions, see [six failure-handling and availability scenarios](FAILURE_SCENARIOS.md),
covering API failover, provider isolation, poison jobs, outbox delivery, refund
recovery and database HA, with an experiment and proof obligation for each.

Use this one domain to study databases, pessimistic locking, optimistic locking,
safe retries, retry storms, and the distributed-system mechanisms around them.
Start with the invariant and a concrete race, then inspect Java and SQL. Follow
the conceptual order below before selecting another system-design build.

This tutorial includes worked lessons and a curriculum for later deep dives.
It does not claim to teach the entire field in one document. Source was inspected
this session; live experiments below are predictions and exercises, not newly
executed results. Existing runtime evidence is in [verification](VERIFICATION.md).
Reading a tutorial or having a verified implementation does not establish mastery.

## 1. Pick the concept; keep the booking scenario

Your primary question is: **What happens when many buyers want the same seat,
requests overlap, the database slows down, or a payment response disappears?**

| Study order | Concepts | Ticket-booking problem |
| --- | --- | --- |
| 1 | Request flow, HTTP, threads, connections, pools, deadlines | Where does Alice's hold request wait, and what does it occupy? |
| 2 | Data modeling, keys, joins, constraints, indexes, transactions, ACID | Which database rule prevents two active bookings for seat 7? |
| 3 | Storage pages/buffers, B-trees, WAL, checkpoints, MVCC, vacuum | What survives an API or database restart? What can concurrent readers see? |
| 4 | Isolation, atomic SQL, pessimistic locks, optimistic versions/CAS, deadlocks | Alice and Bob both see AVAILABLE: who is allowed to create the hold? |
| 5 | Partial failures, idempotency, bounded transaction/HTTP retries | Alice's hold committed but she never received the response: what does retry mean? |
| 6 | Backoff, jitter, retry budgets, retry storms, circuit breakers, bulkheads | Thousands of buyers retry during a lock slowdown: how does it recover? |
| 7 | Queueing, backpressure, admission, rate limits, connection storms | Why can additional API replicas overload the same database? |
| 8 | Replication, lag, consistency models, CAP, quorum, failover | Can a replica display an available seat that the primary already sold? |
| 9 | Leases, fencing, failure detection, consensus, clocks | Can a paused old payment worker apply a result after a new worker takes over? |
| 10 | Partitioning, hot keys, caching, invalidation, stampedes | Can event sharding distribute demand for one popular seat? |
| 11 | Messaging, outbox/inbox, deduplication, ordering, sagas, 2PC | How do booking, payment and refund work survive crashes between steps? |
| 12 | SLOs, capacity, observability, ownership, failure domains, RPO/RTO, migrations | What is the recovery policy for a hot event or primary failure? |

Learn one row at a time: explain it, predict a failure, read the corresponding
source, and do a bounded exercise. The calendar depends on your pace.
Later electives include storage engines/LSM compaction, vector clocks and CRDTs,
anti-entropy, stream watermarks, distributed snapshots, multi-region operation,
security boundaries and Byzantine faults. Some require a different mechanism or
topology; a booking API alone is not evidence that all of them are implemented.

### Implemented, proposed, and production extensions

| Mechanism | What exists in this lab | What remains an exercise or extension |
| --- | --- | --- |
| Pessimistic inventory locking | Seat-first PostgreSQL `FOR UPDATE` transactions | Compare lock wait, timeout and throughput under controlled contention |
| Conditional state transitions | State/expiry checks; conditional payment-lease claims | Explain why these are CAS-like without a seat-version protocol |
| Optimistic seat locking | No seat version column or selectable optimistic hold path | Design a versioned alternative preserving all invariants |
| Safe request replay | Durable buyer/hold-key identity, one checkout intent per booking | Compare a lost response and payload-conflicting replay |
| Payment recovery | Durable receipts, five-second leases, token/expiry checks, scheduled lookup | Real provider idempotency, callbacks and independently available provider |
| Retry-storm protection | Some bounded waits and periodic recovery | No full client retry policy, jittered recovery, retry budget, admission or circuit breaker |
| Replication/consensus | Two APIs share one PostgreSQL primary | No replicas, failover or Raft implementation |
| Partitioning/broker/outbox | Not implemented | Later production design lessons |

**Checkpoint:** conditional SQL, row locks and idempotency already appear in the
same application, but they answer different questions. A proposed mechanism must
remain labeled until its code and appropriate experiments exist.

## 2. Read the actual files in this order

Read [system specification](SYSTEM_SPEC.md), [API reference](API_REFERENCE.md),
[user guide](USER_GUIDE.md), [source relationship](../PARITY.md) and
[verification](VERIFICATION.md) before attempting live experiments. The existing
[IntelliJ walkthrough](INTELLIJ_CODE_STUDY_TUTORIAL.md) supplies debugger steps.

| File | Follow this behavior |
| --- | --- |
| [Models.java](../src/main/java/com/example/booking/Models.java) | Separate hold input, booking state, payment state and reconciliation |
| [BookingController.java](../src/main/java/com/example/booking/BookingController.java) | Hold 201/200, checkout 202/200, domain conflicts and demo reconciliation APIs |
| [V1__booking.sql](../src/main/resources/db/migration/V1__booking.sql) | Seat keys, active-seat unique index, hold identity, one payment per booking |
| [BookingStore.java](../src/main/java/com/example/booking/BookingStore.java) | Transaction boundary, seat-first row locks and database-time expiry |
| [BookingService.java](../src/main/java/com/example/booking/BookingService.java) | Same-key replay, competing holds, checkout, cancellation and expiry |
| [Errors.java](../src/main/java/com/example/booking/Errors.java) | Database exceptions become 503 with `Retry-After: 1` |
| [PaymentProcessor.java](../src/main/java/com/example/booking/PaymentProcessor.java) | Conditional lease claims, unknown outcomes, stale completion rejection |
| [LocalProvider.java](../src/main/java/com/example/booking/LocalProvider.java) | Receipt acceptance commits separately from inventory changes |
| [Maintenance.java](../src/main/java/com/example/booking/Maintenance.java) | Bounded candidate scans and periodic recovery |
| [application.yml](../src/main/resources/application.yml) | Eight pool connections and 32 servlet threads per JVM |
| [BookingIntegrationTest.java](../src/test/java/com/example/booking/BookingIntegrationTest.java) | Real-PostgreSQL races, replay, expiry, callbacks and abandoned leases |

API reading order: create one fixture with `POST /api/demo/events`; browse seats;
submit `POST /api/holds`; retry the identical hold on the other API; submit
checkout; read booking/payment; inspect audit; then study UNKNOWN and late success.
New checkout returns 202 for durable intent, not successful payment.

## 3. The invariant comes before the lock

The lab reserves one numbered seat per booking, not a decrementing stock counter.

```text
(event_id, seat_number) -> at most one HELD/CHECKOUT/CONFIRMED physical row
HELD/CHECKOUT validity  -> database time is strictly before expires_at
same buyer + hold key  -> same booking, with matching event/seat/TTL
one booking            -> at most one payment intent
late payment success   -> reconciliation; never steal a replacement booking
```

An expired hold may remain physically HELD until materialized. Before reassigning
the seat, a transaction changes that row to EXPIRED under the seat lock. Thus
logical expiry and the unique index's physical states are deliberately separate.

```text
Alice -> API A -> transaction -> seat row + active booking + request identity
Bob   -> API B -> transaction -> same PostgreSQL authority
```

An in-process `synchronized` block cannot coordinate these two JVMs. Both must
meet at the shared authority. The seat lock serializes different buyers; a
buyer/key advisory lock serializes the same logical hold request; unique indexes
independently constrain durable cardinality.

**Checkpoint:** an availability response is a snapshot for display. Successful
reservation must recheck authoritative state as part of its write decision.

## 4. Learn the database underneath the Java API

### 4.1 Tables, constraints and transactions

```text
events -> seats -> historical bookings -> payment -> receipt/events
                           \-> booking audit
```

The migration enforces primary/foreign keys, unique `(buyer_id, hold_key)`, one
payment per booking, valid-state checks and this partial unique index:

```sql
CREATE UNIQUE INDEX one_active_booking_per_seat
ON bookings(event_id, seat_number)
WHERE state IN ('HELD','CHECKOUT','CONFIRMED');
```

Read this existing SQL rather than executing it again. It allows historical
expired/cancelled rows while rejecting two physical active rows for the same
seat. It does not itself verify every legal state transition or payment outcome.

ACID asks four separate questions: do transaction effects commit together; do
committed states satisfy encoded constraints; what concurrent histories are
allowed; and what survives failure? ACID consistency is not replica consistency.
Study joins/normalization before denormalized seat views: duplicate authoritative
facts introduce another consistency and recovery responsibility.

### 4.2 Indexes and query plans

```text
seat lookup -> index on event/seat -> relevant row
due work    -> partial expiry/next-at indexes -> bounded candidates
```

B-trees support equality/range access and can serve ordered queries. An index
adds write maintenance; whether it helps depends on selectivity and the plan.
[PostgreSQL index types](https://www.postgresql.org/docs/17/indexes-types.html).

Exercise assumption: ten million historical bookings, one active booking sought
for a specific event/seat. Compare an appropriate indexed lookup with examining
the full history. Inspect estimated versus actual rows and buffers instead of
claiming a latency from row count alone. `EXPLAIN ANALYZE` actually executes the
statement; use read-only fixture queries first.
[Using EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html).

The current API uses bounded offset pages. An indexed cursor is a possible later
lesson, not a current feature. A tie-breaker gives ordering, not a stable snapshot
across separately executed pages.

### 4.3 WAL, buffers and crash recovery

```text
booking insert -> changed buffers + WAL -> durable WAL -> commit response
                                   \-> later data-file flush/checkpoint
restart -> replay required durable WAL -> recover database state
```

With durable commit settings, WAL permits recovery before every changed data page
has reached disk. Corresponding WAL must be durable before data-page writes;
in-memory page changes need not wait for a disk flush. Checkpoints bound recovery
work. [PostgreSQL WAL](https://www.postgresql.org/docs/17/wal-intro.html).

An API crash before commit rolls back its transaction when the database ends the
connection. A crash after commit does not undo the booking. Database recovery and
HTTP response delivery are separate. The existing lab's restart checks do not
establish power-loss behavior or replicated failover guarantees.

The [archived WAL article](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/SDIR-pdf/systemdr-roadmap-sources/028-write-ahead-logs-how-databases-ensure.html)
is background. Its generic undo/redo language is not a precise PostgreSQL recovery
specification; its tuning percentages are not measurements of this lab.

### 4.4 MVCC and isolation

```text
row versions: old committed -> new committed -> in-progress update
ordinary reader chooses visible versions; conflicting writers still coordinate
```

PostgreSQL Read Committed provides statement snapshots. Repeatable Read provides
a stable transaction snapshot, including protection against phantoms, but can
allow write skew. Serializable rejects histories inconsistent with a serial
ordering and requires handling aborted transactions.
[Transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html).

Booking uses short Read Committed transactions with explicit common row locks.
Merely adding `@Transactional` to an unsafe check-then-write is insufficient.
Suppose Alice and Bob each read AVAILABLE, then independently decide to insert.
The active-seat index can reject one, but the service still needs deliberate
transition/replay/error handling rather than treating constraints as a complete
workflow. Read-before-write, lock scope and state lifecycle matter together.

Write skew becomes relevant if you add a rule across different rows, such as
keeping at least one seat in a section reserved for accessibility. Independent
row versions cannot by themselves protect that shared predicate. Study a common
guard row, correctly scoped locks, or serializable transactions with retries.

**Checkpoint:** durability, isolation, indexing and business correctness are
different properties. Explain which one each database mechanism provides.

## 5. Pessimistic locking: today's booking implementation

### 5.1 Alice and Bob compete for seat 7

The existing `lockSeat` executes this lookup with parameters:

```sql
SELECT seat_number FROM seats
WHERE event_id = ? AND seat_number = ? FOR UPDATE;
```

```text
Alice: lock seat -> inspect booking -> expire old hold -> insert HELD -> commit
Bob:         waits for lock ----------------------------> inspect -> conflict
```

The row lock lasts until transaction end. Conflicting writers wait; ordinary
snapshot reads are not blocked by this row lock. Decisions must use state read
after acquiring the relevant lock.
[Explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html).

| Step | Alice | Bob | Durable result |
| --- | --- | --- | --- |
| 1 | Takes seat lock | | No new hold |
| 2 | Reads no active owner, inserts HELD/audit | Waits on seat | Not committed yet |
| 3 | Commits | Can acquire seat lock | Alice owns the hold |
| 4 | | Reads active hold and returns 409 | One active booking |

If Alice rolls back, Bob can succeed. If Bob waits across an old hold's expiry,
the post-lock database-time check decides whether reassignment is now allowed.

### 5.2 What is held, and for how long?

`BookingStore.tx` sets a five-second transaction timeout, two-second lock timeout
and three-second statement timeout. The application has eight JDBC connections
per JVM. They are bounded waits, not a complete end-to-end deadline policy.

Illustrative calculation: a seat's critical section takes 40 ms. Its serialized
path admits at most about `1 / 0.040 = 25` decisions/second before other costs.
Eight waiting connections do not create eight simultaneous owners of that seat.
More API replicas can add contenders without increasing that bottleneck's rate.

Keep provider calls outside inventory transactions. The current code does this:
checkout persists intent; a worker claims and commits; provider acceptance has a
separate commit; result application starts another short inventory transaction.

### 5.3 Deadlocks and ordering

```text
bad future group-booking path:
A owns seat 7 -> waits for seat 8
B owns seat 8 -> waits for seat 7
```

The current convention is seat before booking before payment where those rows
are needed. A future group booking needs a consistent total order among seats
as well. A database-detected deadlock aborts work; some aborted transactions may
be eligible for bounded whole-transaction retry.

**Checkpoint:** name the locked row, the protected invariant, and the exact
release boundary. Never include a remote payment wait inside the seat lock.

## 6. Optimistic locking: a proposed alternative for the same seats

The current seat table has no version column. The following is a design exercise,
not a migration, existing endpoint, or implemented switch.

### 6.1 Build a shared version protocol

Suppose a future seat row includes `version`, initially 12. Alice and Bob both
read the seat and active-booking snapshot. Each starts a fresh write transaction
and tries the conditional update below; `?` represents bound JDBC parameters:

```sql
UPDATE seats SET version = version + 1
WHERE event_id = ? AND seat_number = ? AND version = ?;
```

```text
read version v -> transaction -> compare/increment v -> validate -> persist hold
                                  | one row: may proceed
                                  | zero rows: roll back and re-evaluate
```

That update is not a reservation by itself. After winning the comparison, the
transaction must recheck current ownership/expiry, persist the hold and audit,
and preserve request identity and the active-seat unique index. A rejected
transaction rolls back the version change too.

| Step | Alice | Bob | Seat version |
| --- | --- | --- | --- |
| 1 | Reads 12 | Reads 12 | 12 |
| 2 | Conditional update succeeds; persists hold | May wait on Alice's update | Uncommitted 13 |
| 3 | Commits | | 13 |
| 4 | | Expected version 12 no longer matches; zero rows | 13 |
| 5 | | Reloads, sees Alice's valid hold, returns unavailable | 13 |

Optimistic locking delays validation. It does not make database writes lock-free.
At other isolation levels, a concurrent write may instead produce a serialization
error; the example assumes Read Committed.

### 6.2 Every writer must participate

A trustworthy seat-version design must specify how hold, expiry/reassignment,
cancellation, checkout, confirmation and other inventory-changing paths advance
the relevant version. Mixing a versioned path with an unversioned writer breaks
the assumption that unchanged version means unchanged protected state.

The original [locking reference backend](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Optimistic_locking_vs_Pessimistic_locking/locking-demo/backend/server.js)
illustrates this trap: its optimistic balance path increments version, while its
pessimistic path does not. An optimistic reader of `(1000, v0)` can later write
its computed `1010` after a pessimistic write committed `1100` still at `v0`.
This is a source-derived schedule, not a run performed in this session. Borrow
the learning mechanism, not that mixed-writer defect. Its startup also recreates
its table, so source inspection is sufficient for this lesson.

The booking lab uses JDBC, not JPA entities. A JPA `@Version` annotation would
require a different mapping and still would not make raw SQL writers participate.

### 6.3 When to choose each approach

| Mechanism | Booking use | Failure behavior | Decision signal |
| --- | --- | --- | --- |
| Conditional state/expiry SQL | Existing checkout/confirmation and payment claims | Predicate no longer matches | Rule now requires coordinating more rows |
| Pessimistic row locks | Existing contested seat lifecycle | Wait, timeout, deadlock | Lock/pool waits exceed useful latency budget |
| Optimistic seat version | Proposed alternative with uncommon conflicts | Version conflict; reload and bounded retry/rejection | Attempts per committed operation consume spare capacity |
| Serializable transaction | Proposed cross-row invariant study | Serialization abort; full restart | Abort rate or hot predicates require a different data model |

For a deliberately simplified independent-conflict probability `p`, unlimited
retry attempts average `1 / (1-p)`: 1.053 at `p=.05`, two at `.5`, ten at `.9`.
Real contention is correlated and retries increase it. If an exercise sets an
amplification budget of 1.25, the simplified model crosses it at `p>.20`. This is
an explicit exercise threshold, not a universal rule for switching algorithms.

**Checkpoint:** all strategies must preserve the same booking invariants. Compare
attempt cost, useful commits, conflicts and tail latency, not only HTTP successes.

## 7. Retries: separate correctness from recovery effort

### 7.1 A lost response creates uncertainty

```text
Alice -> POST hold, buyer Alice / key h1 -> database commits booking X
Alice <- response disappears
Alice -> same buyer / key h1 / same payload -> booking X, replayed=true
```

In the implemented `hold` path, the buyer/key advisory lock precedes replay
lookup. The database permanently binds that pair to event, seat and TTL. Retry
returns the booking's current state. It does not create another hold or restart
its expiry. Replaying after expiry can return the same EXPIRED booking.

Idempotency makes repeated logical requests safe; it does not make them free.
Replay still does database work, including locks and expiry materialization.
GET booking also takes locks and materializes expiry in this lab. Aggressive
polling can therefore contend with writes.

For checkout, reuse booking ID, checkout key, scenario and delay. One booking has
one durable intent. A new key does not mean a second checkout is allowed. A real
provider needs its own stable operation identity and reconciliation contract.
[Safe retries with idempotent APIs](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/).

### 7.2 Decide which failures deserve another attempt

| Observed outcome | Booking interpretation | Appropriate action |
| --- | --- | --- |
| 400 validation error | Request is invalid | Correct input; do not blindly retry |
| 404 missing resource | No identified resource | Resolve identity rather than looping |
| 409 SEAT_UNAVAILABLE | Another valid owner exists | Stop attempts for this ownership decision |
| 409 IDEMPOTENCY_CONFLICT | Key/input disagree | Fix logical request identity |
| 503 database unavailable/busy | Possibly transient; write result may be ambiguous | Bounded retry with unchanged key/payload, delay and deadline |
| Network timeout | Commit may or may not have happened | Same-key replay/reconciliation; never assume rollback |
| 202 checkout | Intent durably accepted, outcome pending | Bounded state lookup/recovery, not a new payment intent |
| Payment UNKNOWN | Local acceptance may already exist | Resolve using the same payment identity |
| Confirmed provider FAILURE | Terminal outcome in this contract | Another attempt requires a new legitimate booking workflow |

`Errors.database` maps all `DataAccessException` values to a coarse 503 with
`Retry-After: 1`. A future internal transaction-retry policy must classify causes
more precisely; an integrity or programming error is not automatically transient.

### 7.3 Whole-transaction retry is a different boundary

PostgreSQL serialization failure (`40001`) and deadlock (`40P01`) can justify
bounded transaction restart. Repeat the whole transaction, including reads and
business decisions, after rollback. Do not keep executing the failed transaction
or blindly retry an individual statement.
[Serialization failure handling](https://www.postgresql.org/docs/16/mvcc-serialization-failure-handling.html).

```text
outside transaction: check deadline and retry budget
  start fresh transaction -> read/validate/write -> commit or rollback
outside transaction: release connection -> jittered wait -> next eligible attempt
```

Spring's default proxy transaction mode does not intercept a same-object call to
an annotated method. Make transaction entry explicit, for example with a template
around each attempt. The existing store already uses a template, but it does not
implement a general whole-transaction retry loop.
[Spring transaction boundaries](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html).

**Checkpoint:** identify three separate loops: caller retry, database transaction
restart, and payment-worker recovery. Choose one owner for each failure; retries
at multiple layers can multiply attempts.

## 8. Retry storms: the booking slowdown amplifies itself

### 8.1 Follow the feedback loop

```text
hot seat or slow DB -> lock/pool wait -> timeout/503 -> buyer retry
        ^                                              |
        +---------- more database work <---------------+
```

Hypothetical workload, not a measurement: 100 logical hold requests/second and
three total attempts per request. If every attempt fails, the offered attempt
load can approach 300/second. If failure probability is independently `.5`, the
expected attempts are `1 + .5 + .25 = 1.75`, giving 175 attempts/second. During
overload, failures are correlated and probabilities increase with load.

If three layers each allow three attempts, the innermost dependency may receive
`3 * 3 * 3 = 27` attempts for one logical request. Even perfectly idempotent writes
can exhaust connections and prevent recovery.

### 8.2 Delay, spread, bound, and admit

Backoff spreads attempts across increasing intervals; jitter avoids clients waking
at exactly the same interval. A proposed full-jitter policy can use:

```text
cap(k) = min(maxDelay, baseDelay * 2^k), k=0 for the first retry
without server minimum: delay = random(0, cap(k))
with Retry-After:       delay = serverMinimum + random(0, cap(k))
```

For a teaching choice `baseDelay=100ms`, retry jitter windows are 0–100ms,
0–200ms and 0–400ms. The current lab's `Retry-After: 1` is a one-second minimum
when honored, so its first delayed retry could be 1000–1100ms with this policy.
This is a proposed client policy, not existing Java behavior.
[Exponential backoff and jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/).

Retries need a total attempt cap, a retry budget, and a shared end-to-end deadline.
Each attempt and sleep must fit within remaining time. A timeout does not prove
that the server cancelled its work. Avoid overlapping another attempt while old
work is still consuming capacity whenever the protocol permits it.
[Timeouts, retries and backoff](https://d1.awsstatic.com/builderslibrary/pdfs/timeouts-retries-and-backoff-with-jitter.pdf).

Example policy assumptions: 100 new logical operations/second; retry tokens accrue
at 10/second with a burst cap of 20. Sustained additional retry rate is then capped
at 10/second at that budget's scope. Ten APIs each with their own such budget can
still add 100 retries/second. Specify whether the budget is per caller, process,
event or deployment. Jitter changes timing; it does not cap aggregate load.

| Protection | Booking purpose | Limitation |
| --- | --- | --- |
| Idempotency | Repeat a logical hold/checkout safely | Still consumes resources |
| Bounded retries + deadline | Stop unproductive recovery effort | Cannot restore an unavailable authority |
| Retry budget | Reserve capacity for fresh useful work | Local budgets multiply across processes |
| Admission/waiting room | Reject or queue before occupying DB connections | Does not grant ownership of a seat |
| Bulkhead | Bound concurrent dependency use | Per-process cap is not a global cap |
| Circuit breaker | Temporarily stop calls to a failing dependency | Does not distinguish correctness unless policy does |

Circuit breakers use CLOSED -> OPEN -> bounded HALF_OPEN probes. Reserve probe
slots at admission, release them on completion, and bound concurrent probes. A
popular seat's expected 409 conflicts should not open a database-outage breaker.
Preserve a reconciliation path for already accepted payments rather than starving
it behind new hold traffic.

### 8.3 Learn from the local Python source before borrowing it

The [archived retry-storm article](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/SDIR-pdf/systemdr-roadmap-sources/126-retry-storms-prevention-and-mitigation.html)
introduces backoff, breakers and degradation. Its incident stories and suggested
metric thresholds were not independently verified here; they are not lab results.

Inspected original implementations:

- [Gateway](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/retry_storm_prevention/retry-storms-demo/services/gateway/main.py):
  bounded attempt loop, exponential delay capped at a configured maximum, jitter
  over the upper half of that delay, and a process-local breaker. It retries broad
  exceptions/non-200 responses and checks the breaker only before the loop.
- [Backend](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/retry_storm_prevention/retry-storms-demo/services/backend/main.py):
  configurable failure/latency simulation, but `time.sleep` inside an async path
  blocks its event loop and affects the experiment's concurrency model.
- [Load generator](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/retry_storm_prevention/retry-storms-demo/services/load-generator/main.py):
  each client awaits completion and then sleeps. Its configured RPS is not a fixed
  independent arrival rate when latency rises; distinguish offered and achieved load.

The gateway counts successful half-open completions rather than reserving
in-flight probe slots, so simultaneous probes can exceed the apparent cap.
These source observations explain why a future booking experiment needs deliberate
error classification, deadline budgeting and concurrency accounting. The original
stacks and load scripts were not launched.

### 8.4 What booking recovery does today

`Maintenance.tick` uses a 500ms fixed delay after completion. It scans up to 50
expiry candidates and 20 recoverable payments. Exceptions cause a later tick,
not a jittered global retry budget. For an unresolved receipt, `recover` stores
`next_at = max(ready_at, database_now + 3 seconds)` and releases its lease. Two
replicas compete through conditional five-second lease claims.

These mechanisms make recovery persistent and ownership-aware. They do not
establish retry-storm protection, fairness, a retry cap or a recovery-lag SLO.

**Checkpoint:** safe duplicate handling protects business effects; overload
controls protect scarce resources. Both are necessary.

## 9. Connection storms, queueing and operational signals

```text
HTTP arrival -> servlet capacity -> pool checkout -> DB execution/lock wait
                   queues              queues           more waits
```

Little's Law for a stable boundary is `L = lambda * W`. Hypothetically, 100
operations/second spending 200ms in database service need about 20 concurrent
operations. If that residence time rises to two seconds at the same throughput,
it becomes 200. Adding connections does not create database CPU/I/O capacity.

Two current APIs allow up to `2 * 8 = 16` JDBC pool connections. Ten similarly
configured replicas allow 80, before administrative or other clients. Restarting
them together can produce a connection-establishment burst. Distinguish requests
waiting for existing connections from clients reconnecting or growing pools.

Proposed mitigations: bound dependency admission before pool checkout, stagger
startup/reconnects, reserve operational capacity, limit queues, and reject work
whose deadline makes success impossible. Connection storms remains a deferred
build; studying the concept does not start that project.

For a future controlled experiment, record logical requests, total attempts,
same-key replays, conflicts, useful commits, lock/pool waits, p95/p99 latency,
in-flight work, errors, UNKNOWN age and refund-required backlog. Existing
`/api/stats` reports state counts, not this full storm telemetry.

**Checkpoint:** bound resource use at the correct scope. A thread limit, pool
limit, quota and waiting room each constrain something different.

## 10. Other concepts using the same booking problem

### Replication, consistency and CAP

```text
primary: seat HELD -> asynchronous WAL -> replica: may still show AVAILABLE
```

Replica reads may lag; they cannot authorize a new hold. Asynchronous failover
can lose acknowledged primary changes absent stronger durability arrangements.
Synchronous replication trades latency/availability for defined acknowledgment
requirements. [PostgreSQL replication](https://www.postgresql.org/docs/16/warm-standby.html).

Serializability concerns transaction histories; linearizability also respects
real-time order of completed operations. Eventual consistency permits lag with
convergence under its assumptions. CAP concerns behavior during partitions: a
system cannot promise both linearizable operations and successful service of
every request on all separated sides. It is not a general rule to pick any two
features permanently. Booking should explicitly fail or defer authoritative
holds when it cannot reach its authority rather than sell from stale cache.

For a later quorum exercise, `N=3, W=2, R=2` gives intersecting read/write sets
since `R+W>N`. Intersection alone does not establish linearizability: versions,
concurrent writes, membership, read protocol and repair matter. No quorum
database is implemented in this lab.

### Leases, fencing, time and consensus

```text
worker A claims token t1 -> pauses -> lease expires -> B claims t2
A resumes -> DB checks token/expiry -> rejects stale completion
```

The implemented payment application checks token equality and live lease before
accepting a worker result. The protected database enforces rejection; a worker
believing it owns a lease is insufficient. A UUID token provides equality-based
ownership here; it is not a globally increasing fencing epoch for an arbitrary
external provider.

Hold expiry uses database wall time. Monotonic local time suits elapsed deadline
budgets but cannot be compared directly across machines. Logical clocks help
describe ordering/causality; they do not tell whether a two-minute hold expired.

Raft is a later consensus lesson for replicated state machines: a leader orders
log entries and a majority supports progress under its crash-fault assumptions.
A three-node cluster needs two participating nodes for progress; Raft is not an
algorithm present in the current booking lab.
[Raft paper, section 5](https://raft.github.io/raft.pdf).

### Partitioning, hot keys and caches

```text
event E1 -> shard A; event E2 -> shard B
all buyers for E1/seat7 -> the same authoritative decision
```

Partitioning by event distributes independent events and keeps group-seat
transactions local, but one huge event or seat remains hot. Consistent hashing
chooses ownership and reduces movement on membership changes; it does not make
one indivisible invariant parallel.

Cache metadata and approximate availability for browsing; never treat stale
availability as a successful reservation. TTL expiry alone does not prevent
stampedes. Single-flight can coalesce refreshes in one process; distributed
refresh admission requires shared coordination. None of these cache paths exists
in the present lab.

### Messaging, sagas and external outcomes

```text
persist booking + outbox in one transaction -> publish -> consumer inbox/dedup
payment success too late -> durable refund intent -> reconciliation
```

A future outbox addresses the database-commit/publish gap. Consumers still need
idempotent effects and ordering assumptions. Acknowledging a broker message is
not the same as completing a provider operation. Define ordering per seat,
booking or partition rather than claiming a global order.

A saga coordinates local steps and compensation; refund-required is a durable
obligation, not proof of a refund. Two-phase commit coordinates participating
resource commits but brings coordinator/recovery/availability costs and cannot
enlist a provider that does not support the protocol. The current simulator
shares PostgreSQL and commits separately; it is not distributed ACID or an
independently available payment service.

### SLOs, ownership and safe evolution

Specify separately a reservation-decision latency target, ambiguous-payment
resolution target and refund-backlog target. Good HTTP availability can hide
growing unresolved work. Define who owns alerts, reconciliation and recovery.
RPO concerns allowable data loss; RTO concerns recovery time. Replication is not
a substitute for tested backups/restores.

Adding optimistic versions later requires compatible deployment sequencing:
introduce schema, make every relevant writer participate, validate behavior,
then enable a compared strategy gradually. Rolling back to unversioned writers
can break the protocol. Measure correctness, attempts and tail latency through
the migration, and keep rollback behavior explicit.

**Checkpoint:** distinguish a concept applied to the design from a mechanism
implemented and tested in the local application.

## 11. Exercises: use the current lab; compare extensions deliberately

Use fresh uniquely named fixtures and the project's supplied commands. Exercises
retain data; none ran during this documentation task.

| Exercise | Predict before inspecting/running | Relevant code/test |
| --- | --- | --- |
| Two buyers, one seat | One new hold; another gets seat unavailable | `hold`; `manyBuyersCannotOversellOneSeat` |
| Same buyer/key via A and B | Same booking ID; one original admission | `concurrentDuplicateHoldReturnsOneDurableBooking` |
| Same key, different seat or TTL | Explicit identity conflict | `holdKeyCannotChangePayloadButIsScopedToBuyer` |
| Repeat expired hold key | Same EXPIRED booking; expiry not extended | `expiredReplayNeverCreatesAnotherHold` |
| Duplicate checkout | One payment ID; matching replay | `concurrentCheckoutHasOneIntentAndChangedInputConflicts` |
| UNKNOWN receipt | Resolve same payment; no second intent | `unknownOutcomeRecoversFromDurableReceipt` |
| Callback crosses expiry behind lock | No confirmation; refund required on success | `expiryAfterWaitingForSeatLockPreventsConfirmation` |
| Stale worker resumes | Old token cannot apply completion | `abandonedLeaseRecoversAndStaleWorkerCannotApply` |
| Bypass service lock, insert duplicate active owner | Unique index rejects invalid cardinality | `databaseConstraintRejectsSecondActiveBookingEvenWithoutServiceLock` |

Run selected tests only when you want an experiment; the documented test setup
uses real PostgreSQL via Testcontainers. Existing evidence is historical, not a
new run performed by writing this guide.

Proposed optimistic comparison: preserve the same hold/key/expiry semantics,
design complete version participation, then compare the pessimistic and optimistic
strategies on independent seats versus one hot seat. Explicitly test mixed writers
and cancellation/expiry races. Do not remove the unique constraint to make results
look better.

Proposed retry-storm comparison: fixed finite request count, bounded concurrency,
fresh event, defined injected latency, and total time/attempt caps. Compare no
retry, immediate retry, backoff, jitter, budget, and admission. Never retry expected
seat-unavailable conflicts as dependency failures. Include duplicate-key traffic
and separate unique-buyer traffic; they stress different locks. The future harness
must distinguish offered arrivals from a completion-paced closed-loop client.
No controlled storm harness or selectable optimistic mode has been built here.

## 12. End-to-end mental model

The database serializes ownership; request identity resolves duplicate admission;
payment identity resolves external uncertainty; leases coordinate workers; bounded
admission and retries keep recovery from exhausting the same database. Caches and
replicas may accelerate browsing without becoming seat ownership authorities.

```text
buyer -> deadline/admission [proposed] -> API A/B
  -> buyer/key coordination -> short seat transaction -> durable hold/checkout
  -> recoverable payment claim -> separately committed local receipt
  -> token/state/expiry check -> confirm OR refund-required
  <- current outcome through same-key replay / bounded lookup
```

## 13. End-to-end scenario walkthroughs

### Normal write

1. Alice submits hold key h1 for seat 7; transaction checks replay and locks seat.
2. No valid active owner exists; insert HELD/audit and commit before 201.
3. Checkout persists one intent before 202; worker claims it and commits its lease.
4. Receipt acceptance commits separately; application locks inventory and checks
   expiry, outcome and ownership before confirming.

### Normal read

1. Seat browsing computes availability from one database statement snapshot.
2. Booking GET acquires locks and materializes expiry; it is not a lock-free read.
3. A later reservation must recheck authoritative state regardless of what browsing
   displayed.

### One-node failure

1. API A dies after committing a hold and before delivering its response.
2. Alice replays h1 through B; persisted identity returns the same booking.
3. A claimed payment can be recovered after its lease expires. If the sole database
   fails instead, both APIs lose their authority and reject/defer operations.

### Slow replica — hypothetical future topology

1. The primary commits Alice's hold; an asynchronous standby still displays free.
2. Bob submits a hold to the primary authority and cannot acquire that seat.
3. Measure lag; define primary routing for ownership-dependent reads. With synchronous
   replication, acknowledgment wait depends on the configured replication policy.

### Network partition

1. A cannot reach PostgreSQL while B can; A's availability cache would be insufficient.
2. A returns a bounded error, potentially with an ambiguous write outcome.
3. Same-key replay through a reachable authority resolves it; retries do not create
   independent ownership in the disconnected process.

### Node recovery

1. A restarts and discovers persisted bookings/intents rather than rebuilding from
   local timers.
2. Old payment claim tokens are accepted only if still current and live; expired
   work can be reclaimed.
3. Expiry/recovery scans converge physical state. Inspect unresolved age and audits
   rather than declaring recovery from process health alone.

### Node addition

1. Add API C to the same database; it uses the same locks and identity constraints.
2. Potential JDBC capacity rises from 16 to 24 connections with current pool sizing.
3. Check database headroom and admission; single-seat serialization is unchanged.
4. Adding a database shard later also requires moving ownership/data and handling
   routing during migration, not merely adding an API address.

### Control-plane/coordinator failure

1. The current lab has no separate consensus or routing coordinator; database
   access is a direct shared dependency.
2. In a future sharded/HA topology, stale placement or unavailable failover
   coordination can prevent safe ownership decisions despite stored data existing.
3. Specify whether cached routing remains valid, how old primaries are fenced,
   and when writes must stop. This is a future design, not current HA evidence.

## 14. Concept-relationship table

| Mechanism | The question it answers |
| --- | --- |
| Constraint | What durable states are forbidden? |
| Transaction/isolation | Which grouped effects and concurrent histories are allowed? |
| WAL | What can be recovered after a crash? |
| Index | How much data must the query examine? |
| Pessimistic row lock | Who may decide on this seat right now? |
| Optimistic version | Did protected state change since I read it? |
| Idempotency key | Is this the same logical request? |
| Backoff/jitter/budget | When and how much recovery traffic may retry? |
| Admission/bulkhead | How much work may occupy scarce resources? |
| Circuit breaker | Should we temporarily stop this dependency traffic? |
| Lease/token check | Can this worker still apply a result? |
| Replication/consensus | Which copies/order remain authoritative under failure? |
| Partitioning | Which authority owns this event/seat? |
| Cache | Which reads can avoid the authority with acceptable staleness? |
| Outbox/inbox | How does durable work cross a commit/delivery gap? |
| Saga/reconciliation | What obligation remains after a partial business outcome? |

## 15. Key concepts to retain

1. An available display is not an authoritative reservation.
2. A timeout is uncertainty, not proof of rollback or payment failure.
3. Row locks, versions, constraints and idempotency protect different properties.
4. Optimistic locking requires all writers to honor its protocol.
5. Retrying a permanent business conflict wastes capacity.
6. Jitter spreads work; budgets and admission bound work.
7. A lease protects effects only when the protected resource checks ownership.
8. Replicas, caches and more API nodes do not eliminate a hot seat's invariant.
9. Late success creates reconciliation work, never permission to steal a seat.
10. Proposed designs, implemented mechanisms, executed tests and learner mastery
    are separate records.

## 16. Principal-engineer drill-down questions

- Where exactly does a hold or confirmation decision become atomic? What happens
  if expiry is crossed while waiting for a lock?
- Which writers must advance a proposed seat version, and how can a rolling
  upgrade safely introduce that protocol?
- How do you distinguish a useful retry from contention that your own retries create?
- Who owns the deadline and retry budget across browser, gateway, API and worker?
- How can a waiting room protect inventory capacity without unfairly starving
  already accepted payment reconciliation?
- What happens to acknowledged bookings during primary failover? State RPO/RTO
  and clock assumptions instead of saying replication makes it safe.
- What is the operational signal that UNKNOWN/refund-required work is stuck?
- Which invariant would be hardest to preserve when adding group bookings or
  changing partition ownership during a hot event?

Next study session: explain sections 3–5 using Alice and Bob, trace `hold` and
`lockSeat`, then predict same-key replay. Continue with the optimistic design in
section 6 and retry-storm lessons in sections 7–9. Pick comparison code only after
its scope is explicitly selected; application behavior was not changed here.

## 17. Reference material and evidence boundary

Local references came first: the Java files linked above, original locking
backend/tests, original Python gateway/backend/load generator, and archived WAL
and retry-storm articles. Original sources remain preserved at
`D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main`; the separate article
archive is indexed by
[_MANIFEST.csv](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/SDIR-pdf/systemdr-roadmap-sources/_MANIFEST.csv).

Official database, Spring, AWS author explanations and the Raft paper are linked
at the relevant concepts for semantic clarification. Compose currently selects
PostgreSQL 16; some storage/query references use PostgreSQL 17 documentation for
the shared fundamentals. These references do not imply an upgrade.

Source inspection and documentation checks were performed for this tutorial;
no application tests, workload, infrastructure restart, optimistic migration or
retry-storm experiment was run. For previously executed guarantees and their
limits, read [VERIFICATION](VERIFICATION.md).
