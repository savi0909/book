# Movie booking: shows, atomic seat groups and payment retries

This is the current backend domain at `D:/sd-book-my-show`. The inherited generic
ticketing endpoints/studies remain separately available. The movie expansion is
new October5 work; it is not backdated into the reconstructed source schedule.
Read [the movie API reference](MOVIE_API_REFERENCE.md) alongside this walkthrough.

## Your requirements

| Requirement | Implementation |
| --- | --- |
|5-100 screens/multiplex | List of uniquely named screens, validated at creation |
|200-500 seats/screen | Stored capacity and a numbered layout |
|Two or three categories | Positive A/B/C percentages adding to100 |
|A10/B20/C70 roughly | Default layout; rounding remainder goes to the final category |
|Multiple shows on the same day | Separate show inventory and multiplex-local date listing |
|Multiple selected seats |1-10 seats, all-or-nothing reservation |
|95% first call, another4.5% retry,0.5% fail | Stored mock plan; one logical automatic retry |
|Failed payment retains seats | HELD until original expiry; fresh key creates a fresh payment |
|Thousands of users later | [Saved simulation plan](MOVIE_LOAD_SIMULATION_PLAN.md), not a current benchmark |

A200-seat default screen has20 A,40 B,140 C;500 seats gives50/100/350. For201
the counts are20/40/141. Concrete seat numbers belong to categories. Two-category
layouts can explicitly choose A30/C70 or A10/C90; the service does not guess.

Show creation freezes prices. Example A50000, B30000, C15000 minor units mean
INR500/300/150. Seats1,21,61 in the200-seat map total95000 minor units. These are
example business prices. The server calculates the amount; checkout never trusts
a caller amount. Another show can have different prices for the same screen.

## Domain relationships

```mermaid
flowchart LR
  M[Multiplex and time zone] --> S[5-100 screens]
  S --> L[200-500 seat category template]
  F[Movie and runtime] --> H[Dated show]
  S --> H
  H --> I[Frozen show seats / prices]
  I --> B[Atomic group booking]
  B --> P[Payment attempt 1]
  B --> Q[Fresh payment after definite failure]
  P --> R[Immutable logical-call receipts]
  Q --> R
```

A screen is physical. A show occupies it for one movie and time interval. Seat42
in two shows has two different `(show_id,seat_number)` identities and can be sold
independently. Scheduling locks the screen row and rejects overlapping runtime
or turnaround intervals. Different screens may host simultaneous movies.

The show timestamp is an instant; the multiplex stores an IANA zone such as
Asia/Kolkata. A date query converts that local midnight and next midnight to
instants, rather than assuming every local day is24 hours. An overnight show
belongs to its start date. New shows must start in the future.

## IntelliJ reading order

1. [V5 migration](../src/main/resources/db/migration/V5__movie_booking.sql): catalog,
   frozen show seats, group members, attempts and receipts.
2. [MovieModels](../src/main/java/com/example/booking/MovieModels.java) and
   [MovieController](../src/main/java/com/example/booking/MovieController.java):
   bounds, shapes, customer routes and local fixtures.
3. [MovieCatalog](../src/main/java/com/example/booking/MovieCatalog.java): layout
   rounding, schedule locking, date conversion and per-show inventory.
4. [MovieStore](../src/main/java/com/example/booking/MovieStore.java): canonical
   locks, immutable historical members, expiry and owner-checked release.
5. [MovieBookingService](../src/main/java/com/example/booking/MovieBookingService.java):
   group hold, durable key replay and cancellation.
6. [MoviePaymentMock](../src/main/java/com/example/booking/MoviePaymentMock.java) and
   [MoviePaymentService](../src/main/java/com/example/booking/MoviePaymentService.java):
   plan, receipt commit, lease, retry and fresh-payment admission.
7. [MovieBookingIntegrationTest](../src/test/java/com/example/booking/MovieBookingIntegrationTest.java):
   races, rollback, payment histories, expiry and stale leases.

## Trace one atomic group

Alice requests61,1,21 with buyer/key identity. The service sorts to1,21,61 and
rejects duplicates. A scoped advisory lock serializes her hold key. Replay
compares show, canonical seat set and TTL; reversed input order is equivalent.

For a new hold, lock every chosen show-seat in ascending order. After the locks,
read current owner bookings and database-clock validity. If any member is still
held/confirmed, reject the entire request. Otherwise insert the booking, snapshot
each selected category/price and assign all seat-owner pointers, in one commit.

Bob's group2,21,62 conflicts with Alice's21. Neither2 nor62 becomes held. Sorting
also prevents opposite input orders from taking locks in opposite orders. It does
not eliminate every timeout or provide fair admission under arbitrary load.

Later inventory changes lock historical group members in the same order, then
booking, then payment. Release clears only pointers still owned by that booking.
An expired group may already have replacements; its later cleanup cannot erase
their pointers. Confirmation additionally verifies it owns every selected seat.

Deadline is the earlier of database now+TTL and show start. Equality is expired.
Availability reads treat expired pointers as logically free before the sweeper
updates old booking state. A new hold replaces them under seat locks. Every
admission/confirmation uses database time, not a browser clock.

## Payment percentages and identities

The percentages apply across **all payment attempts**:9500 of10000 plans succeed
at call1;450 succeed at call2;50 fail call2. Thus90% of the initial5% needing a
retry succeed on that retry. Applying4.5% to that5% would be a different model.

A new UUID hashes to a stored bucket0-9999:0-9499 first success,9500-9949 retry
success,9950-9999 failure. A live random sample fluctuates around these ratios.
The unit test exhaustively checks the10000 intervals; it does not promise exact
95/4.5/0.5 proportions in every live run. A fresh user attempt has a new plan.

```mermaid
stateDiagram-v2
  HELD --> PAYMENT_PENDING: new payment key
  PAYMENT_PENDING --> CONFIRMED: success / all seats owned / live deadline
  PAYMENT_PENDING --> HELD: second logical call fails
  HELD --> PAYMENT_PENDING: fresh user payment key
  HELD --> EXPIRED: original deadline
  PAYMENT_PENDING --> EXPIRED: original deadline
  HELD --> CANCELLED: cancel
  PAYMENT_PENDING --> CANCELLED: cancel
  CONFIRMED --> CANCELLED: cancel / refund obligation
```

Automatic retry retains the same payment UUID. Step1 transient failure schedules
step2 after500ms. Receipt identity is `(payment_id,provider_step)`, persisted in
its own transaction. A response/application loss can cause another dispatch,
which discovers the same logical receipt. Dispatch count may exceed two after
infrastructure recovery; there are at most two logical provider steps.

After second-call FAILURE, return the group to HELD without changing expiry or
seat ownership. Same failed key discovers the old attempt and cannot pay again.
A new key admits payment number2 only while the hold is live and owns all seats.
Only one unresolved payment is allowed; a partial unique index backs that rule.

Claim commits a5s lease/random token before mock acceptance. Receipt commits
separately. Apply locks the entire group, verifies current token/unexpired lease,
stored receipt/current payment and deadline, then updates payment/booking together.
No inventory lock spans acceptance. Source and mock still share PostgreSQL.

Late SUCCESS after cancellation/expiry/reassignment records SUCCEEDED and
`refundRequired=true`; it cannot steal replacement seats. Confirmed cancellation
also flags a refund. This is a simulated obligation, without actual refund execution.

## Build and exercise

```powershell
Set-Location D:/sd-book-my-show
mvn -B -ntp verify
docker compose -f compose.yml -f compose.failover.yml build api-a api-b
docker compose -f compose.yml -f compose.failover.yml up -d --wait
```

Use A8130/B8131/gateway8132/PostgreSQL5553. Movie mock is in-process; the inherited
`compose.provider.yml` is for generic ticket studies, not this movie workflow.
Ordinary checkout uses `{}` and automatically chooses its plan.202 means durable
intent; poll GET booking to discover the result. If a response is uncertain,
replay the original key before considering a new payment.

For deterministic learning, the optional overlay enables `testBucket` and manual
reconcile, and disables both legacy/movie loops on both replicas:

```powershell
docker compose -f compose.yml -f compose.failover.yml -f compose.movie.yml config --quiet
docker compose -f compose.yml -f compose.failover.yml -f compose.movie.yml up -d --wait
node scripts/learn-movie.mjs
npx --yes newman@6.2.2 run postman/movie-booking.postman_collection.json -e postman/movie-booking.postman_environment.json
```

These runs create retained fixtures. Run them sequentially. The runtime harness
performs a finite32-request A/B race; the collection walks all three payment
paths. Neither is a sustained benchmark. Manual controls return404 at gateway.

Restore normal workers by omitting the movie overlay:

```powershell
docker compose -f compose.yml -f compose.failover.yml up -d --wait
```

Default `testBucket` input rejects400; manual reconcile returns404. Scheduler
checks every200ms with batches up to20; this is not a throughput guarantee.
Stop with the same selected Compose files and `stop`; retain all volumes.

## Debugger and exercises

Break at `lockSeats`, final hold assignment, payment claim, mock `accept` and
payment `apply`. Watch sorted seats, show/booking/payment IDs, step, token and
deadline. Pausing while locks/leases are live changes the experiment.

Exercise1: overlap2,21,62 with1,21,61 and predict rollback. Exercise2: in manual
mode fail bucket9950, submit a new key with9500, then compare both IDs/deadlines.
Exercise3: hold the same seat numbers in two shows and explain their independence.

Interview points: inventory identity is show/seat; sorted locks provide atomic
groups; automatic retry differs from a fresh user attempt; confirmation checks
current ownership/deadline; scale claims require measured contention and drain.

No frontend, production auth, real payment/refund, movie notification outbox or
independent provider HA is delivered. Generic outbox/poison studies remain separate
and do not automatically apply to movie tables. Read [future work](FUTURE_WORK.md),
[ADR](ADR_001_MOVIE_GROUP_BOOKING.md) and [verification](MOVIE_VERIFICATION.md).
