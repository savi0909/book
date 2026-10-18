# Study the order outbox and saga

[Tutorial index and learning guide](TUTORIAL_INDEX.md) · One lesson at a time; runtime exercises require a selected hands-on session.

Date: 2026-10-18. This lesson explains the implemented optional workflow, using
one movie order from HTTP acceptance through confirmed inventory. Start with the
[runbook](ORDER_SAGA_RUNBOOK.md) for commands/configuration and the
[verification record](ORDER_SAGA_VERIFICATION.md) for actual test results.
Reading this lesson does not start services or select a load run.

## The problem this code solves

A database transaction can save an order and its outgoing message atomically.
It cannot atomically commit PostgreSQL, Kafka and an HTTP payment provider in one
ordinary local transaction. A process can crash after any one of them succeeds.
The implementation stores the next action before performing it and uses durable
identities to recognize repeats. A saga then decides how to continue or compensate
from participant facts.

The new `/api/orders` route is default-off. Existing generic/movie booking APIs
coexist with it; they do not gain saga behavior automatically. The implementation
uses logical Order, Inventory and Payment participants inside the booking Java
process with separate transactions in the same database. The provider is the
copied `mock-payment-gateway` process, with a separate schema in that database.
The original `D:/customer-api/mock-payment-gateway` project stays untouched.
Kafka is the only message broker. The provider is simulated and performs no real
billing. This topology teaches protocol and recovery, not database or broker HA.

```mermaid
sequenceDiagram
    participant C as Caller
    participant O as Order + DB
    participant K as Kafka
    participant I as Inventory + DB
    participant P as Payment + DB
    participant G as Copied mock gateway
    C->>O: POST /api/orders + customer + idempotency key
    O->>O: Order + saga + request response + outbox COMMIT
    O-->>C: 202 + orderId + statusUrl
    O->>K: ReserveInventory (relay after commit)
    K->>I: ReserveInventory
    I->>I: Inbox + hold + reply outbox COMMIT
    I->>K: InventoryResult HELD
    K->>O: InventoryResult
    O->>K: CapturePayment (new outbox)
    K->>P: CapturePayment
    P->>P: Persist operation COMMIT
    P->>G: GET operation UUID; POST same UUID only if absent
    G-->>P: CAPTURED
    P->>P: Result + reply outbox COMMIT
    P->>K: PaymentResult CAPTURED
    K->>O: PaymentResult
    O->>K: CommitReservation
    K->>I: CommitReservation
    I->>I: Commit authoritative inventory + reply outbox
    I->>K: InventoryResult COMMITTED
    K->>O: InventoryResult
    O->>O: CONFIRMED + COMPLETED + OrderConfirmed outbox
```

Each arrow to Kafka is shorthand for a committed outbox row followed by relay
publication. Participant effects and their reply outbox commit together. Provider
HTTP calls happen outside the booking database transaction.

## First pass in IntelliJ: follow one accepted request

Open these files in order; no infrastructure is necessary for this reading pass.

| File | Read these methods | Question to answer |
| --- | --- | --- |
| [OrderController.java](../src/main/java/com/example/booking/OrderController.java) | `authorize`, `create`, `get`, `cancel` | Why is `202` returned before a booking exists? |
| [OrderService.java](../src/main/java/com/example/booking/OrderService.java) | `create`, `get` | Which records survive together, and who prices the order? |
| [OrderStore.java](../src/main/java/com/example/booking/OrderStore.java) | `snapshot`, `command`, `outbox` | Which identity stays stable when an action repeats? |
| [V8__order_saga.sql](../src/main/resources/db/migration/V8__order_saga.sql) | `orders`, `order_requests`, `order_sagas`, `order_commands`, `order_outbox` | What constraints stop illegal stored states? |

The trusted caller sends show UUID and 1–10 distinct seat numbers. Authorization
uses a shared development bearer secret plus caller-asserted `X-Customer-Id`.
Owner checks are useful within that trust contract; this is not real customer
authentication. Provider and operator tokens are separate from the API token.

`create` sorts the seat numbers and serializes a `CanonicalRequest` record with
fixed fields before hashing, so the same selection in another order has the same
canonical request on both API replicas. A transaction-scoped advisory lock serializes
the same customer/key before an order is allocated. A matching replay returns
the originally stored response; a changed request receives 409. This avoids the
concurrent-loser orphan-order problem.

Prices come from `movie_show_seats`, not the caller. `orders.items` stores selected
seats and `amount_minor` stores their total with INR currency. Inventory later
compares the actual held amount to this snapshot. A price change causes rejection,
not an unexpected charge. The snapshot is not a seat lock: another customer can
win the seats before the asynchronous reservation runs.

Inside one `store.db.tx`, creation saves the order, saga, `OrderCreated`,
`ReserveInventory`, API request hash and exact `202` response. A rollback leaves
none of them. `OrderStore.outbox` refuses calls outside a transaction. It does
not use an after-commit listener to create the durable message.

Pause here: after the HTTP response, which committed row proves there is still
work to do if the API process immediately crashes? It is the pending outbox
command, backed by the saga and command identity.

## Second pass: delivery can repeat safely

Open [OrderRelay.java](../src/main/java/com/example/booking/OrderRelay.java),
then [OrderKafka.java](../src/main/java/com/example/booking/OrderKafka.java).

`claim` locks due PENDING rows with `FOR UPDATE SKIP LOCKED` in a short transaction.
It marks them IN_PROGRESS, increments attempts, and stores worker, fresh token
and expiry. The transaction commits before `publish`. Another replica can claim
other rows instead of waiting on this batch. The default claim is at most ten,
the minimum of the batch/concurrency/capacity limits.

Publication awaits Kafka acknowledgment outside the transaction. `acknowledge`,
`fail` and `heartbeat` update only a matching row/token with an unexpired lease.
That is fencing: an old worker cannot complete a newer owner's claim. Expired
claim recovery is bounded to 100 rows and clears ownership before retry or DEAD.
Heartbeat support exists; the default dispatcher relies on bounded sends and the
validated lease rather than running a heartbeat loop.

| Crash point | Durable result and recovery |
| --- | --- |
| Before claim commits | Claim rolls back; another relay can claim |
| After claim commits, before send | Expiry recovery makes the row due again |
| After Kafka accepts, before PUBLISHED update | Retry may send the same envelope again |
| Consumer effect commits, before offset commits | Kafka redelivers; durable inbox detects the duplicate |

Kafka producer idempotence and `acks=all` support reliable producer delivery, but
do not replace application inbox/operation identities when the relay republishes
after a crash. See [Apache producer configuration](https://kafka.apache.org/39/configuration/producer-configs/).

The order UUID is the Kafka key. Both API replicas join the same
`booking-saga-v1` group and subscribe to Inventory commands, Payment commands and
Order replies. They dispatch to the appropriate logical participant. The event
topic has no built-in ticket/notification consumer. Different future subscribers
need their own groups and deduplication.

`enable.auto.commit=false` is essential: `poll` calls `commitSync(offset+1)` only
after a local business transaction or durable quarantine commits. If that offset
commit fails, it seeks back and safely repeats the record. This follows the
[KafkaConsumer manual-offset model](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html).
The database transaction and offset commit are separate; no distributed
exactly-once guarantee is made.

`OrderStore.inbox` uses `(consumer_name,message_id)` plus a payload hash. The same
message ID with changed content is invalid, not a harmless duplicate. Invalid
envelopes or order keys quarantine immediately; repeated other failures quarantine
on the fifth recorded failure. The original payload and Kafka location remain in
`order_message_failures`. Quarantine lets a partition move forward and creates an
operator obligation; it does not complete the order.

An order key gives partition affinity within a topic. Multiple topics, concurrent
relays and retries still allow different business actions to arrive out of order.
State guards and tombstones provide correctness without assuming global FIFO.

## Third pass: confirm only after Inventory commits

Read `OrderService.consume` and `advance`, then
[OrderInventory.java](../src/main/java/com/example/booking/OrderInventory.java).

| Durable facts | Next decision |
| --- | --- |
| Inventory UNKNOWN | Wait/query the original reservation; do not assume rejection |
| Inventory HELD, payment not yet captured | PAYING; issue original CapturePayment |
| Inventory HELD, payment CAPTURED | COMMITTING; issue CommitReservation |
| Inventory COMMITTED, payment CAPTURED, no stop intent | CONFIRMED / COMPLETED; emit OrderConfirmed |
| Inventory REJECTED/EXPIRED or payment DECLINED | Persist failure intent; compensate |

A payment success alone never confirms an order. `CommitReservation` must win
against expiry/release in the inventory state machine. Inventory reuses the
existing movie group-hold implementation and sorted seat locking. It locks/checks
the authoritative movie booking, expires it if due and checks ownership before
transitioning HELD to CONFIRMED. A timestamp copied into the saga is not ownership.

`order_inventory` maps the original reserve command to its movie booking and
participant version. Replies are correlated with a stored command and saga.
Lower inventory versions cannot overwrite newer facts. Released/refunded facts
cannot regress when an old HELD/CAPTURED reply arrives. Contradictory settled facts
can require manual review instead of silently reviving an order.

Saga-created movie bookings use the existing movie tables, but their lifecycle is
coordinated by these commands. `CancelBooking` is distinct from releasing a hold:
a committed reservation needs explicit booking cancellation during compensation.
The normal past-day movie purger excludes shows with saga-linked bookings, and
V8 adds the referencing inventory FK. Old maintainer binaries must be drained
before new saga writers start; an old process does not know that exclusion.

## Fourth pass: Payment closes the HTTP crash gap

Read [OrderPayment.java](../src/main/java/com/example/booking/OrderPayment.java):
`consume`, `insert`, `process`, `complete`. Then open the gateway's
[SagaOperationService.java](../mock-payment-gateway/src/main/java/com/aaztra/mockpg/saga/SagaOperationService.java)
and [contract](../mock-payment-gateway/README.md).

There are three separate commits around a provider operation:

1. Payment consumes the command, inserts the inbox and stable CAPTURE/REFUND
   operation, and writes its immediate reply outbox in one local transaction.
2. A worker claims the persisted operation and commits its attempt/lease before
   HTTP. It first GETs `/mock-pg/saga/operations/{operationId}`. Only on 404 does
   it POST the same operation UUID and persisted parameters.
3. The fenced result update and fresh reply outbox commit together. A stale
   completion token cannot overwrite a newer lease owner.

If the gateway captured money but the response or booking result commit was lost,
the next GET returns the same provider result. A 404 does not prove an earlier
POST cannot still commit; that is why a subsequent POST must reuse the same UUID.
The gateway serializes operations, atomically stores its immutable result with
mock payment effects, and rejects a changed payload or second operation for the
same order/type. It stores these outcomes in its own schema. No provider HTTP
call is hidden inside an inventory transaction.

The original capture command UUID is the capture operation/provider key. Refund
has a separate deterministic UUID derived from it and references the original
capture. Only a full matching amount/order/currency refund is accepted. Business
deduplication remains necessary even if a duplicate command has a new message ID.

Timeouts become UNKNOWN and retry within a durable budget. ACTION_REQUIRED is
neither success nor decline; it remains unresolved, and this reference workflow
does not implement a customer authentication UI. Exhaustion or rejected refunds
becomes MANUAL_REVIEW. The worker does not invent a replacement charge or declare
unknown funds settled.

## Fifth pass: cancellation is durable intent

Read `OrderService.cancel`, the stop branch of `advance`, Inventory's release
branch and Payment's `CancelPayment` branch.

Cancel persists intent on the saga before cleanup commands are sent. There are
two important tombstones, both tied to the original business operation:

| Cleanup arrives first | What is persisted | What delayed work sees |
| --- | --- | --- |
| ReleaseReservation before ReserveInventory | RELEASED inventory row, stop requested, no booking | Reserve cannot allocate seats under that reserve UUID |
| CancelPayment before CapturePayment | CANCELLED capture operation, stop requested | Capture cannot create a fresh operation under that capture UUID |

An unstarted persisted capture can become CANCELLED. Once an attempt might have
reached the provider, cancellation records stop intent and reconciles the same
operation. A late CAPTURED fact leads to RefundPayment. It does not restore the
happy path. Likewise a committed inventory fact during cancellation leads to
CancelBooking, not a promise that releasing an old hold undoes a committed ticket.

The order reaches CANCELLED (user intent) or FAILED (business failure) only after
both obligations are clear: payment is DECLINED/CANCELLED/REFUNDED and inventory
is RELEASED/REJECTED/EXPIRED. Unknown funds, unknown ownership and rejected refunds
remain compensation or manual-review work. Confirmed-order cancellation is
explicitly unsupported and returns 409; it needs a separate ticket/refund policy.

## Recovery and retention are part of correctness

[OrderWorkers.java](../src/main/java/com/example/booking/OrderWorkers.java) schedules
recovery, expiry, payment processing, relay and Kafka polling under the admission
gate. Due sagas are locked order-before-saga, in bounded batches. Recovery saves
QueryInventory/QueryPayment commands and next deadlines, rather than making HTTP
calls inside those locks. Business deadline expiry requests compensation; recovery
exhaustion stops automatic action at MANUAL_REVIEW with obligations retained.

[OrderOperations.java](../src/main/java/com/example/booking/OrderOperations.java)
contains three audited controls: replay one DEAD outbox identity, reconcile one
manual-review payment operation, and resume one manual-review saga. A payment
manual review needs its payment reconcile scheduled before saga resume. Resume
increments the query generation and preserves original business identities and
cancellation/failure intent. It cannot override an immutable rejected provider
result. Controls require a distinct operator token and local opt-in; HAProxy
blocks them. There is no quarantine replay endpoint. See the runbook before
recovery; editing inboxes or charging under a new identity defeats the protocol.

The optional Kafka topics retain records for 14 days. Deduplication evidence must
outlive supported replay, and unresolved obligations must outlive that horizon.
API request bindings, inbox hashes, operations, provider outcomes, cancellation
tombstones and audit records currently have no automatic cleanup. Purging a
tombstone early can turn a safe delayed command into a new charge or reservation.
Any future bounded cleanup SQL needs a reviewed cutoff and explicit approval;
cleanup indexes alone are not a deletion policy.

## Review fixes to study

These are narrow correctness lessons from the implementation review. Read one
test, predict its failure, then locate the production change; you do not need
to execute the test during a reading session.

### A matching retry can reach a different JVM

`OrderService.create` hashes a `CanonicalRequest` record with a fixed field
sequence. `Map.of` iteration order can vary between JVMs; sorting seat numbers
alone would not make serialized map keys stable. Two replicas could otherwise
disagree about the same customer's request and return a false 409.

Open `apiRequestHashDoesNotDependOnMapIterationAcrossReplicas` in
[OrderSagaIntegrationTest](../src/test/java/com/example/booking/OrderSagaIntegrationTest.java).
It uses serializers with opposite map ordering to model that difference without
depending on JVM randomness. The accepted response must replay unchanged.

Checkpoint: which part must be canonical—the idempotency key, the request hash,
or both the key's scope and the request representation?

### Security and routing must recognize the same path

The copied gateway protects all routed saga operations with its token filter.
A raw prefix check missed a path such as `/mock-pg/saga;foo/operations/{id}`:
Spring MVC can remove matrix parameters when matching a controller. A filter
that sees a different route could skip authentication.

Open [SagaTokenFilter](../mock-payment-gateway/src/main/java/com/aaztra/mockpg/saga/SagaTokenFilter.java).
It now uses Spring `RequestPath` and `PathPattern` matching, including decoded
segments and matrix parameters. Provider tests exercise GET/POST with missing,
wrong and valid tokens across path variants. Literal encoded-semicolon forms
that do not route remain 404. No payment token is logged or placed in a URL.

Checkpoint: why is a correct constant-time token comparison insufficient if
the filter fails to recognize a protected route?

### Manual review must retain the stop decision

`OrderService.manual` persists `failure_requested=true` before compensation
commands are saved. Resuming that saga returns to COMPENSATING, not RESERVING.
The original release/refund may still be waiting in Kafka; a delayed success
reply must not enable forward confirmation while cleanup remains queued.

Open `manualReviewCannotResumeForwardWhileCompensationIsQueued`. It commits
inventory, delays the reply, exhausts recovery, then resumes before cleanup is
delivered. The workflow must settle as FAILED with refunded funds and released
inventory. User cancellation instead targets CANCELLED. Participant versions
also prevent an older COMMITTED reply from replacing a newer RELEASED fact.

Checkpoint: why is changing `phase` alone insufficient when an operator resumes
work after the process has already committed compensation intent?

## Four identities to keep separate

| Boundary | Stable identity | What it prevents |
| --- | --- | --- |
| HTTP acceptance | Customer + idempotency key + request hash | Duplicate order creation or changed request reuse |
| Kafka delivery | Consumer + message UUID + payload hash | Repeating the same consumed effects |
| Participant operation | Reserve/capture/refund command or operation UUID | A second business action under another message UUID |
| Provider side effect | Persisted operation UUID + immutable parameters/result | A second capture/refund after HTTP ambiguity |

Outbox DEAD and quarantine are delivery states. They are independent of whether
money moved or seats were allocated. Recovery must inspect all relevant boundaries.

## Interview points

1. An outbox makes business state and intent atomic in one database transaction.
   Publication stays at least once; consumers need durable deduplication.
2. A lease token plus unexpired ownership fences completion/failure/heartbeat.
   It cannot cancel a broker send already in flight, so duplicates remain possible.
3. A saga confirms only after captured payment and authoritative inventory commit.
   Compensation resolves funds and seats independently before declaring failure.
4. Persist cancellation tombstones and provider operation identities before delayed
   work. An absent query result is not proof that an in-flight command cannot run.
5. Manual offsets follow local commit; provider reconciliation follows the original
   UUID. These compose recoverable local transactions, not distributed exactly once.

## One paper exercise

Assume Payment captures successfully, its HTTP response is lost, and the customer
cancels before the worker persists CAPTURED. Trace the next durable records without
running services. Name (a) the UUID used for the next gateway GET/POST, (b) the stop
intent that prevents order confirmation, (c) the refund identity, and (d) the two
participant facts required before CANCELLED. Then locate each decision in
`OrderPayment.process`, `consume`, `OrderService.advance` and `OrderStore.command`.
If either participant stays UNKNOWN, explain why an operator obligation is safer
than forcing a terminal order status.

## Primary references

The code and linked tests define this repository's contract. For the underlying
Kafka mechanisms, read [producer configuration](https://kafka.apache.org/39/configuration/producer-configs/)
and [KafkaConsumer documentation](https://kafka.apache.org/39/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html).
Producer idempotence does not deduplicate a new application-level send after a
database/broker crash window; the inbox and operation identities provide that
boundary. For route matching and parsed paths, see
[Spring MVC request mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html).
