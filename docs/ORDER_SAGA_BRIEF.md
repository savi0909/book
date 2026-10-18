# Ticket-booking Order Service: Outbox + Saga

**Codex implementation brief · Java / Spring Boot · PostgreSQL reference design**

> Reconstructed from the referenced chat's available questions. Its assistant replies were unavailable, so this is a complete replacement brief, not a verbatim export. Repository code has not been inspected.

## Read this first — 60 seconds

- **Outbox:** commit business changes + outgoing messages together. Relay messages afterward.
- **Saga:** persist booking progress; recover or compensate across services.
- **Inbox:** deduplicate incoming messages in the same transaction as their effects.
- **Delivery:** at least once. Duplicate messages are expected.
- **Success:** payment captured **and** inventory durably committed.
- **Timeout:** outcome unknown; investigate before deciding success or failure.

**Chosen flow:** reserve seats → capture payment → commit reservation → confirm order.

This deliberately permits a short “paid, booking pending” period. Failed finalization requires a refund. If the provider supports authorization/capture, design that alternative explicitly before changing this sequence.

## 1. Copy-ready instructions for Codex

Update the existing Order Service to implement this brief. Preserve its Java/Spring versions, broker, API conventions, and database migration tooling. Use PostgreSQL SQL below only when PostgreSQL is the actual database; document equivalent locking/index behavior otherwise.

- [ ] Inspect the order API, persistence, transactions, broker configuration, payment/inventory contracts, and tests first.
- [ ] Implement a **durable orchestrated Saga** owned by Order Service.
- [ ] Keep each local state transition and its outbox inserts in one database transaction.
- [ ] Require participant services to persist their operation state and outgoing replies reliably too.
- [ ] Deliver in the stages below; include migrations, configuration, tests, and a recovery runbook.
- [ ] Document missing participant capabilities; do not claim end-to-end correctness using Order Service changes alone.

### Non-negotiable rules

- No broker/provider/network call inside a business database transaction.
- No in-memory-only workflow, retry counter, deadline, or compensation plan.
- No “exactly once” claim across database, broker, and payment provider.
- `PUBLISHED` means broker acknowledgment; it does **not** mean booking succeeded.
- `DEAD` means delivery needs intervention; it does **not** mean payment failed.
- A duplicate or late message must never charge twice, oversell, or revive a cancelled booking.

## 2. Implementation stages

### Stage A — Contracts and state machine

- [ ] Define commands: `ReserveInventory`, `CapturePayment`, `CommitReservation`, `ReleaseReservation`, `CancelPayment`, `RefundPayment`, and status queries.
- [ ] Define corresponding success/rejection replies; distinguish permanent rejection from transient failure and unknown outcome.
- [ ] Freeze immutable message payloads; version the schema.
- [ ] Validate price server-side; store quantity, seat IDs, currency, and integer minor-unit amount snapshots.
- [ ] Define legal state transitions and cancellation policy before handlers.

**Message envelope:** `messageId`, `type`, `schemaVersion`, `orderId`, `sagaId`, `commandId`, `correlationId`, `causationId`, `occurredAt`, `payload`.

- Retransmission: same `messageId`, same payload.
- New business action: new `messageId` and durable `commandId`.
- Reply references the originating `commandId`; never infer the step from `orderId` alone.

**Order states:** `PENDING`, `CONFIRMED`, `CANCELLING`, `CANCELLED`, `FAILED`, `MANUAL_REVIEW`.

**Saga phases:** `RESERVING`, `PAYING`, `COMMITTING`, `COMPENSATING`, `COMPLETED`, `CANCELLED`, `FAILED`, `MANUAL_REVIEW`.

Store payment/inventory states separately. An order status alone cannot describe partial success.

### Stage B — Schema and indexes

Reference schema: adapt names and extend existing tables. UUIDs come from the application; timestamps use UTC instants.

```sql
CREATE TABLE orders (
  id uuid PRIMARY KEY,
  customer_id uuid NOT NULL,
  status text NOT NULL,
  amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
  currency varchar(3) NOT NULL,
  items jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_requests (
  customer_id uuid NOT NULL,
  idempotency_key text NOT NULL,
  request_hash text NOT NULL,
  order_id uuid NOT NULL REFERENCES orders(id),
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (customer_id, idempotency_key)
);

CREATE TABLE order_sagas (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
  phase text NOT NULL,
  inventory_state text NOT NULL,
  payment_state text NOT NULL,
  reservation_id text,
  reservation_expires_at timestamptz,
  payment_id text,
  deadline_at timestamptz,
  next_action_at timestamptz,
  recovery_attempts integer NOT NULL DEFAULT 0,
  last_error text,
  context jsonb NOT NULL DEFAULT '{}',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL REFERENCES orders(id),
  saga_id uuid NOT NULL REFERENCES order_sagas(id),
  dedupe_key text NOT NULL UNIQUE,
  event_type text NOT NULL,
  destination text NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','IN_PROGRESS','PUBLISHED','DEAD')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  available_at timestamptz NOT NULL DEFAULT now(),
  locked_by text,
  lock_token uuid,
  locked_until timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  CHECK ((status = 'IN_PROGRESS') =
    (locked_by IS NOT NULL AND lock_token IS NOT NULL
     AND locked_until IS NOT NULL)),
  CHECK (status = 'IN_PROGRESS' OR
    (locked_by IS NULL AND lock_token IS NULL AND locked_until IS NULL))
);

CREATE TABLE inbox_messages (
  consumer_name text NOT NULL,
  message_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_name, message_id)
);

CREATE INDEX outbox_ready_idx
  ON outbox_events (available_at, id) WHERE status = 'PENDING';
CREATE INDEX outbox_expired_idx
  ON outbox_events (locked_until, id) WHERE status = 'IN_PROGRESS';
CREATE INDEX outbox_order_idx ON outbox_events (aggregate_id, created_at);
CREATE INDEX outbox_dead_idx
  ON outbox_events (updated_at, id) WHERE status = 'DEAD';
CREATE INDEX outbox_cleanup_idx
  ON outbox_events (published_at, id) WHERE status = 'PUBLISHED';
CREATE INDEX saga_due_idx ON order_sagas (next_action_at, id)
  WHERE phase IN ('RESERVING','PAYING','COMMITTING','COMPENSATING');
CREATE INDEX orders_customer_idx ON orders (customer_id, created_at DESC);
CREATE INDEX inbox_cleanup_idx ON inbox_messages (processed_at);
```

- Add database checks for allowed order/Saga/participant states in final migrations.
- Primary/unique constraints already create indexes; do not duplicate them.
- Due-work queries must match partial-index predicates. Validate with representative data and `EXPLAIN (ANALYZE, BUFFERS)` in a test environment.
- `context` stores durable command IDs, provider operation keys, compensation flags, cancellation intent, and step generation; promote frequently queried fields to columns.
- Set `next_action_at=NULL` on terminal/manual-review workflows; explicit operator actions can resume them.

**Participant storage contracts:**

- Inventory: reservation ID, unique reserve-command ID, order, seat/quantity, state, expiry, version; index `(expires_at, id)` for held reservations. Enforce exclusive active seat ownership or atomic capacity decrement in Inventory's database.
- Payment: unique `(order_id, operation_type, generation)`, immutable provider key, request hash, provider ID, status, attempts, next reconciliation time. Uniquely identify refund operations too.
- Both: inbox + outbox, durable cancellation/release intent, operation lookup API, idempotent repeated commands.

### Stage C — Atomic order creation and consumption

**`POST /orders` with an idempotency key:**

1. Authenticate; validate canonical request and server-side price.
2. In one transaction: insert `PENDING` order, `RESERVING` Saga, `OrderCreated` event, `ReserveInventory` command, and stored `202` response.
3. Commit; return `orderId` and status URL. `GET /orders/{id}` reports progress.
4. Same customer/key + same request hash → stored response. Different hash → `409`.
5. Concurrent unique-key loser rolls back its **whole** transaction; load winner in a fresh transaction. Leave no orphan order.

**Every incoming reply, in one transaction:**

1. Insert inbox row using `ON CONFLICT DO NOTHING`; duplicate → no effects.
2. Lock order then Saga in a consistent order, or use versioned compare-and-set with retries.
3. Check phase, command ID, generation, cancellation intent, and participant state.
4. Update facts/state + insert next commands/events + update deadline.
5. Commit, then acknowledge the broker message. Rollback → redelivery.

Do not let a framework auto-ack before commit. Exhausted malformed-message retries go to a durable quarantine/DLQ with an alert. Deferred messages need durable storage or a scheduled status query; never silently acknowledge and lose necessary work.

### Stage D — Multiple pollers with leases

Use native SQL/JDBC for claiming. Keep claim, publish, and completion as separate operations.

**1. Claim a bounded batch in a short transaction:**

```sql
WITH picked AS (
  SELECT id FROM outbox_events
  WHERE status = 'PENDING' AND available_at <= now()
    AND attempts < :max_attempts
  ORDER BY available_at, id
  LIMIT :batch_size
  FOR UPDATE SKIP LOCKED
)
UPDATE outbox_events o
SET status = 'IN_PROGRESS', attempts = o.attempts + 1,
    locked_by = :worker_id, lock_token = :new_claim_token,
    locked_until = now() + (:lease_seconds * interval '1 second'),
    updated_at = now()
FROM picked p WHERE o.id = p.id
RETURNING o.*;
```

Commit **before** publishing. A fresh UUID token per claim batch is sufficient because updates also match row ID. A worker ID alone cannot fence an old attempt.

**2. Publish outside the transaction:** await the broker's durable acknowledgment. Use `orderId` as the routing/partition key where supported.

**3. Complete using ownership checks:**

```sql
UPDATE outbox_events
SET status = 'PUBLISHED', published_at = now(), updated_at = now(),
    locked_by = NULL, lock_token = NULL, locked_until = NULL,
    last_error = NULL
WHERE id = :id AND status = 'IN_PROGRESS'
  AND lock_token = :claim_token AND locked_until > now();
```

Zero updated rows → ownership lost; do not overwrite the new worker's result. Apply the same predicate to failure updates and heartbeat extensions.

**4. Recover expired claims:** each poll cycle, run a bounded, short transaction selecting expired `IN_PROGRESS` rows with `FOR UPDATE SKIP LOCKED`, ordered by `(locked_until,id)`.

- Set `DEAD` if `attempts >= max_attempts`; otherwise `PENDING` with `available_at=now()`.
- Clear all lease fields; set `updated_at` and `last_error='lease expired; delivery unknown'`.
- Then normal claiming can pick them up. Reclaim consumes no extra attempt; the next claim does.

**If poller 1 claims 100 rows and crashes:**

- Before claim commit: transaction rollback makes rows available after DB connection cleanup.
- After claim commit: lease expiry + recovery makes rows available to poller 2.
- After publish, before completion: poller 2 may publish duplicates. Inbox/business idempotency absorbs them.

**Bounds:** claim only available worker capacity. Lease must cover batch waiting + publish/ack time; otherwise use fenced heartbeats or smaller batches. Bound DB lock/statement timeouts too.

Leases fence database updates, not an already-running broker send. Do not promise zero duplicate publications.

### Stage E — Retries, recovery, and operations

- [ ] Configure poll interval, batch/concurrency limits, lease, broker timeout, max claims, and backoff.
- [ ] Transient delivery failure → retry while `attempts < max_attempts`; otherwise `DEAD`. Use full jitter: random delay in `[0, min(cap, base × 2^(attempts−1))]`.
- [ ] Invalid payload/destination → `DEAD` immediately; retain payload and diagnostic reason.
- [ ] Recovery scheduler locks due Sagas with `SKIP LOCKED`, persists status-query/compensation commands, and advances `next_action_at` in the same transaction. Network calls happen later.
- [ ] Crash after scheduling recovery → commands remain in outbox. Restart scans durable deadlines.
- [ ] Budget exhausted or facts contradictory → `MANUAL_REVIEW`, alert, preserve unresolved obligations.

Suggested starting values: poll `500 ms`, maximum batch `20`, publish concurrency `10`, lease `30 s`, broker timeout `5 s`, max claims `10`, backoff base `1 s` / cap `5 min`. These are tuning assumptions, not universal settings. Set inventory/payment deadlines from real provider SLAs and reservation TTLs.

## 3. Outbox fields — exactly when they change

| Moment | Persisted values |
|---|---|
| Created with business change | Stable UUID/envelope/payload/dedupe key; `PENDING`; attempts `0`; available/created/updated `now`; lease/error/published fields `NULL` |
| Claimed | `IN_PROGRESS`; attempts `+1`; worker + fresh token + expiry; updated `now` |
| Broker confirms | `PUBLISHED`; published/updated `now`; clear lease/error |
| Retryable send/ack failure, budget remains | `PENDING`; retain attempts; available `now + jitter`; sanitized error; updated `now`; clear lease |
| Permanent failure / budget exhausted | `DEAD`; retain attempts/payload/error; updated `now`; clear lease |
| Lease expires | Recovery follows Stage D; previous delivery may have succeeded |
| Audited replay | Same event ID/payload; `PENDING`; available `now`; reset delivery budget if approved; record who/why and prior attempts separately |

Keep `created_at` and message identity immutable. Set `published_at` only after acknowledged publication. A downstream business rejection never changes an already-published outbox row.

## 4. Happy path — data and process flow

```text
Client → Order DB [order + Saga + outbox] → relay → broker
       → Inventory [inbox + reservation + reply outbox]
       → Order [inbox + Saga + CapturePayment outbox]
       → Payment [durable operation → provider → result + reply outbox]
       → Order [inbox + Saga + CommitReservation outbox]
       → Inventory [inbox + committed booking + reply outbox]
       → Order [inbox + CONFIRMED + completed Saga + OrderConfirmed outbox]
```

Each bracketed local transition is atomic. Payment's external provider call is **between** local transactions; reconciliation closes its crash window.

1. `InventoryReserved`: save reservation ID/expiry; move to `PAYING`; enqueue capture.
2. `PaymentCaptured`: save provider payment ID; move to `COMMITTING`; enqueue inventory commit.
3. `ReservationCommitted`: confirm order only when both durable success facts match the current workflow.
4. Emit `OrderConfirmed`; ticket/notification consumers deduplicate. Notification failure does not undo the booking.

Inventory must atomically decide commit versus expiry/release under its own locks. An unexpired timestamp cached by Order Service is not proof of ownership.

## 5. Failure decisions — what happens next

| Trigger | Required action / outcome |
|---|---|
| Order transaction rolls back | No order, Saga, request record, or outgoing messages survive |
| Broker unavailable | Retry delivery; order remains pending; monitor backlog age |
| Inventory rejects reservation | Mark failed once no payment/hold obligation exists; publish `OrderFailed` |
| Reserve request times out | Query by reserve-command ID; do not reserve again under a new identity |
| Payment definitively declined | Persist failure; compensate inventory; fail only after hold is released/expired and payment settled |
| Provider timeout / crash after charge | Set payment `UNKNOWN`; reconcile same operation; never issue a fresh-key charge |
| Payment succeeds; inventory commit rejects/expires | Enter compensation; refund captured payment; release any remaining hold |
| Inventory commit acknowledgment lost | Query reservation; committed → continue; expired/released → compensate; unknown → reconcile |
| Pending-order cancellation or deadline | Persist cancellation intent; resolve in-flight payment/inventory operations; issue idempotent cleanup |
| Late reserve success after cancellation | Record reservation; ensure release; never resume payment |
| Late payment success after cancellation | Record captured funds; ensure refund; never confirm |
| Release reaches Inventory before reserve | Persist cancellation tombstone for that reserve-command ID; later reserve cannot allocate |
| Inventory commit races with cancellation | Query authoritative result; committed booking requires `CancelBooking`, not merely releasing a hold |
| Refund/release/cancel fails temporarily | Remain compensating; retry/reconcile; no premature terminal status |
| Refund rejected permanently / unresolved outcome | `MANUAL_REVIEW`; retain amount/resource obligations and alert |
| Duplicate or stale reply | Deduplicate; apply relevant compensation facts; no state regression |
| Poison message / exhausted outbox delivery | Quarantine or `DEAD`; alert and recover workflow separately |

**Compensation completion:** persist separate “funds settled” and “inventory cleared” flags. Set `CANCELLED` for user cancellation or `FAILED` for booking failure only when both obligations are resolved.

Cancellation after `CONFIRMED` needs explicit refund/ticket policy and a separate cancellation workflow. If unsupported, reject it explicitly; never silently reuse the pending-order cleanup path.

## 6. Payment, inventory, and idempotency details

### Payment: close the external-call gap

1. Payment Service inserts a durable operation before calling the provider.
2. Derive stable provider key from persisted operation identity; reuse identical parameters.
3. Call provider outside the database transaction.
4. Persist result + reply outbox together. Crash at step 3 → status query, webhook, or same-key retry according to provider contract.
5. Use separate stable keys for charge and refund; track refunded amount and prevent over-refund.

- Payment authentication/customer action is a pending state, not a decline; expire/reconcile against the reservation deadline.
- `CancelPayment` persists stop intent against the original capture operation, even if capture has not arrived. Suppress unstarted capture; reconcile in-flight capture and refund any settled charge. An absent operation lookup alone does not prove a queued capture cannot still execute.
- Verify webhook signatures; durably accept/deduplicate before returning success; process asynchronously.
- Provider notifications may duplicate or arrive out of order. Reconcile authoritative status when needed.
- Persist provider IDs early; provider idempotency retention is finite. After key expiry, reconcile/escalate before any retry that could create a new charge.

### Inventory: prevent overselling and stranded holds

- Reserve idempotently against the original command ID.
- Enforce seat uniqueness/capacity in Inventory's transaction.
- Persist TTL; expiry worker atomically releases only `HELD` reservations.
- `CommitReservation`, release, and expiry must share one authoritative state machine.
- Release by command ID must work even when the reservation reply never arrived.
- Committed inventory stays allocated until an explicit idempotent cancellation operation succeeds.

### Four independent deduplication boundaries

- **API:** `(customer, idempotency key)` + canonical request hash.
- **Message delivery:** `(consumer name, messageId)` in inbox.
- **Business operation:** stable `commandId`/operation identity across retransmissions and recovery.
- **Provider:** persisted charge/refund keys + authoritative payment IDs.

Message-ID deduplication alone cannot prevent the same business operation arriving under a new message ID.

### Ordering and late events

- Routing by order ID helps broker ordering, but concurrent pollers/retries can still publish out of sequence.
- This design relies on guarded transitions, command correlation, cancellation tombstones, and reconciliation.
- Ignore stale state transitions; still process late facts requiring compensation.
- If consumers require strict order, add serialized per-order publication with persisted sequence/cursor and an explicit policy for blocked/dead predecessors. A partition key alone is insufficient.

## 7. Spring implementation boundaries

- Use `@Transactional` service methods or `TransactionTemplate`; ensure proxy interception and correct rollback rules. Avoid transactional self-invocation assumptions.
- Insert outbox rows directly within the business transaction. An `AFTER_COMMIT` listener is not the durable handoff.
- Keep scheduled polling outside business transactions; claim/complete via separate transactional components.
- Use row locks or `@Version` consistently for Saga/order transitions; handle optimistic conflicts by retrying the entire transaction.
- Virtual threads can help blocking concurrency, but still bound database connections, provider calls, broker sends, and outstanding leases. They provide no durability guarantee.

## 8. Test checklist — required before completion

Use real PostgreSQL integration tests, a representative broker, and a controllable provider stub. Inject crashes around transaction and acknowledgment boundaries.

- [ ] Rollback leaves neither business data nor outbox/request records.
- [ ] Concurrent same-key API calls create exactly one order; changed payload gets `409`.
- [ ] Multiple pollers claim disjoint rows while leases are valid.
- [ ] Crash before/after claim commit; crash after broker ack; full 100-row abandoned batch recovers.
- [ ] Old token cannot heartbeat, complete, or reschedule a reclaimed row.
- [ ] Slow sends/lease expiry allow duplicate delivery without duplicate business effects.
- [ ] Retry timing, attempt counting, permanent failure, `DEAD`, audited replay, and recovery budgets work.
- [ ] Consumer crash before/after commit; broker ack loss; duplicate IDs and duplicate operations with different message IDs.
- [ ] Every happy-path phase persists correctly and resumes after restart.
- [ ] Concurrent bookings cannot oversell the same seat/capacity.
- [ ] Reserve/commit/release/expiry races and cancellation-before-reserve are safe.
- [ ] Decline, customer-action timeout, unknown charge, crash after charge, late success, duplicate/out-of-order webhook.
- [ ] Charge success + commit failure triggers one effective refund and inventory cleanup.
- [ ] Lost commit reply queries Inventory instead of assuming failure.
- [ ] Cancellation racing with capture/commit converges without revival or stranded funds/seats.
- [ ] Failed/unknown refund or release stays compensating/manual-review; never falsely terminal.
- [ ] Late/duplicate replies cannot regress completed state; financial exceptions remain visible.
- [ ] Query plans use expected indexes at realistic backlog sizes.

## 9. Completion checklist and recovery runbook

- [ ] Ship configuration, migrations, participant contracts, state transition rules, and integration tests.
- [ ] Metrics: oldest pending event age, pending/dead count, expired leases, publish errors, overdue Sagas, unresolved payments/refunds.
- [ ] Correlate logs by order/Saga/message/command; sanitize errors and exclude sensitive payment data.
- [ ] Operator recovery: inspect authoritative participant state → repair contract/configuration → replay the same delivery or schedule the missing compensation → verify funds and inventory → audit resolution.
- [ ] Never manually mark an order confirmed from outbox status or replay a charge under a new provider key.
- [ ] Retention: clean published rows in bounded batches; keep unresolved/dead rows. Preserve API/inbox/operation/tombstone deduplication records for at least the supported retry/replay horizon.
- [ ] Backfill existing pending orders deliberately; deploy compatible consumers before producers emit new schemas.

**Done means:** a crash can delay a booking, but every paid/unconfirmed or cancelled/allocated case has durable recovery work or an explicit operator obligation.

## Primary references

The schemas, flow, and tuning defaults above are proposed implementation choices. These sources support the underlying mechanisms:

- [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html): atomic business/outbox writes and duplicate-safe relay consumption.
- [Saga](https://microservices.io/patterns/data/saga.html): local transactions and explicit compensations.
- [PostgreSQL SELECT locking](https://www.postgresql.org/docs/current/sql-select.html): `FOR UPDATE SKIP LOCKED` for competing queue workers.
- [Spring transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html): transaction phases and `AFTER_COMMIT` default.
- [Stripe idempotent requests](https://docs.stripe.com/api/idempotent_requests) and [webhooks](https://docs.stripe.com/webhooks): concrete provider examples; verify your actual provider's guarantees.
