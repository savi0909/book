# Six failure-handling and availability scenarios for ticket booking

Latest delivery2026-10-04: scenarios1–3 are implemented in separate studies.
Scenario3 [tutorial](POISON_JOB_TUTORIAL.md) and [verification](VERIFICATION.md)
supersede the pre-implementation observation below. It adds per-claimed-item
failure handling, persistent quarantine/history and bounded keyed redrive.
Scenarios4–6 remain proposals; stop after the selected scenario3 delivery.

Updated: 2026-10-04. Scenario1 is implemented in the optional failover overlay;
see [its tutorial and interview points](API_FAILOVER_TUTORIAL.md). Scenarios2/3
are authorized next in separate deliveries;4–6 remain proposals. Preserve seat,
expiry, request-key and payment invariants. Read the
[foundations tutorial](DISTRIBUTED_SYSTEMS_FOUNDATIONS.md) for locking and retry
basics; [verification](VERIFICATION.md) records existing execution evidence.

Start with **API failover**, **provider isolation**, and **poison-job isolation**.
They add distinct lessons while building on the existing two APIs and durable
payment recovery. Add outbox and refund workflows next; database HA is a larger,
separate topology exercise.

| Priority | Scenario | Main concepts | Current starting point |
| --- | --- | --- | --- |
| 1 | API crashes or is restarted during checkout | Load balancing, readiness, draining, ambiguous responses | Implemented optional HAProxy8107, drain controls and crash/graceful experiments |
| 2 | Payment provider becomes slow or unavailable | Bulkheads, circuit breaker, deadline/retry budgets, graceful degradation | Implemented optional independent Java stub, slots/deadlines/breaker/budgets |
| 3 | One recovery item fails repeatedly | Poison jobs, per-item isolation, quarantine, redrive, fairness | Implemented durable quarantine/history and bounded keyed redrive; see scenario3 tutorial |
| 4 | Booking commits but its confirmation event is lost | Transactional outbox, delivery retries, inbox deduplication | Booking/audit transaction exists; no notification outbox |
| 5 | Refund succeeds but its response is lost | Saga compensation, durable refund identity, reconciliation | REFUND_REQUIRED and simulated acknowledgment exist |
| 6 | PostgreSQL primary fails or becomes partitioned | Replication, fencing, failover/failback, RPO/RTO | One PostgreSQL primary; no database HA |

## 1. API failover and graceful restart

Implemented design below; use [the detailed walkthrough](API_FAILOVER_TUTORIAL.md)
and [verification](VERIFICATION.md) for current code, commands and executed results.

**Failure story:** API A commits Alice's checkout, then dies before returning 202.
Alice retries through a stable application address and reaches B. Separately,
compare that crash with a planned rolling restart while requests are in flight.

```text
client -> shared entry -> A (crashes)
                    \-> B -> shared PostgreSQL -> original checkout intent
```

**Add:** a local load balancer, explicit readiness/draining behavior, bounded
shutdown time, and an explicit retry policy. Preserve checkout keys and payloads
when the caller retries. Do not let the proxy blindly replay every write:
`POST /api/demo/events`, for example, has no idempotency contract.

Spring Boot 3.5 supports graceful shutdown and a configurable shutdown grace
period. Framework support is only one part of draining: verify the load balancer
stops new traffic, in-flight requests complete or become safely replayable, and
background claims recover after termination.
[Spring Boot graceful shutdown](https://docs.spring.io/spring-boot/3.5/reference/web/graceful-shutdown.html).

**Prove:** one payment intent after retry, continued new bookings through B, and
bounded recovery of work claimed by A. Record the detection/routing gap and failed
requests rather than claiming zero interruption. Test abrupt death separately
from graceful termination. A lone local proxy becomes another single point of
failure; this first exercise demonstrates API-process failover only.

**Read/edit later:** [compose.yml](../compose.yml),
[BookingController](../src/main/java/com/example/booking/BookingController.java),
[application.yml](../src/main/resources/application.yml), and
[PaymentProcessor](../src/main/java/com/example/booking/PaymentProcessor.java).
`/health` is process-only; overall `/actuator/health` includes the database.
Define readiness deliberately. A shared provider outage should not cause every
otherwise useful API to be removed or restarted indiscriminately.

## 2. Provider outage without exhausting booking capacity

Implemented: [detailed tutorial](PROVIDER_ISOLATION_TUTORIAL.md) and [evidence](VERIFICATION.md).
The design notes below motivated the extension; actual limits are two client slots
per API and two processing slots at the one stub, not the illustrative four below.

**Failure story:** the provider takes ten seconds, fails intermittently, or accepts
payment and loses the response. Browse/hold requests should retain capacity while
accepted payment intents remain discoverable and recoverable.

```text
booking requests -> short DB transactions
payment recovery -> bounded provider slots -> slow provider simulator
                         \-> defer durably when budget is exhausted
```

**Implemented:** an independently controlled local provider stub; bounded provider-call
concurrency; one retry owner; per-attempt and overall deadlines; jittered retry
scheduling; a retry budget; and bounded half-open breaker probes. Retain the same
payment identity. Record UNKNOWN where acceptance is ambiguous rather than
inventing FAILURE. Provider work must stay outside inventory transactions.

**Prove:** concurrency never exceeds its configured scope, in-flight work actually
remains bounded after caller timeout, attempts stop at the chosen budget, and
healthy booking operations still complete. Timeout is not proof that the remote
operation stopped. Track reconciliation backlog age and recovery rate after the
provider returns. Define when to stop admitting new checkout intents if the
backlog exceeds the useful hold lifetime or operational budget.

An illustrative sizing choice of four provider slots per API permits eight calls
across two APIs. At one second/call, that is at most roughly eight completed calls
per second before overhead. A sustained arrival rate of twelve payment intents
per second would grow backlog by roughly four per second. Measure these values;
they are assumptions, not the lab's capacity.

**Read/edit later:** [LocalProvider](../src/main/java/com/example/booking/LocalProvider.java),
[PaymentProcessor](../src/main/java/com/example/booking/PaymentProcessor.java),
[Maintenance](../src/main/java/com/example/booking/Maintenance.java).
The default simulator shares PostgreSQL; the new optional stub has independent
process/storage. The default cannot demonstrate an independent
provider outage merely by stopping that database.

## 3. Poison-job isolation and controlled redrive

Implemented: [detailed tutorial](POISON_JOB_TUTORIAL.md). The original observations
below describe the motivation before this delivery; current claims/evidence are
in [verification](VERIFICATION.md).

**Failure story:** one persisted recovery item deterministically fails, while
other buyers have valid pending payments. Repeatedly processing that item must
not prevent unrelated work from progressing.

**Current source observation:** `recoverBatch` processes candidate IDs in a loop
without a per-item catch. An exception escapes to `Maintenance.tick`, which wraps
expiry and payment recovery in one catch. An expiry exception can skip payment
recovery for that tick; a recovery exception can skip the remaining candidates.
This exposes a batch-interruption risk, not proof of permanent starvation in all
schedules. Existing leases can temporarily make a failed payment ineligible.

**Add:** per-item failure classification, durable attempt/error/next-at records,
backoff and isolation between expiry and payment processing. Quarantine repeatedly
failing work for investigation and controlled redrive using the original identity.
A database table is sufficient for a first dead-letter-style exercise; a broker
is not required.

**Prove:** put a deterministic failing fixture before several healthy items;
healthy work still progresses. Verify bounded attempts, visible quarantine reason,
and safe replay after correcting the cause. Model a global DB outage separately:
do not quarantine every payment as malformed because its shared dependency failed.

An uncertain payment must retain its reconciliation obligation when automatic
retries stop. Quarantine does not mean the payment failed or the money was refunded.
Alert on age, count and repeated redrive failure; identify an operational owner.

**Read/edit later:** `recoverBatch`, `expireBatch`, `Maintenance.tick`, and a new
additive migration for recovery metadata if selected. Use safe deterministic
exceptions in a gated test fixture rather than an actual parser/OOM crash.

## 4. Confirmation delivery across the commit/publish gap

**Failure story:** Alice's booking becomes CONFIRMED, but the API dies before
scheduling her ticket email. Later, a dispatcher publishes an event and dies
before recording delivery, causing a duplicate publication after restart.

```text
transaction: CONFIRMED + audit + outbox event
commit -> dispatcher -> local notification consumer -> deduplicated receipt
```

**Add:** an outbox event in the same transaction as the booking transition;
recoverable dispatcher ownership; and a consumer inbox with stable event identity.
Keep business effects and inbox deduplication atomic at the consumer. Outbox
dispatch is generally at least once, so downstream duplicate handling remains
necessary. [Transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).

**Prove:** crash at both boundaries. Every committed confirmation has durable
delivery work; no rolled-back confirmation produces a valid event; duplicates
produce one local notification receipt. Reorder CONFIRMED and CANCELLED events
and define per-booking sequence/version handling. Do not let a late confirmation
reactivate a cancelled ticket in a downstream projection.

A local receipt sink keeps this experiment bounded and avoids real sends.
Exactly-once email delivery would require a separate provider contract and cannot
be inferred from a unique inbox row. Observe oldest-undelivered age and backlog
drain rate, not just dispatcher process health.

**Read/edit later:** the confirmation transaction in `PaymentProcessor.apply`,
cancellation in `BookingService.cancel`, and
[schema](../src/main/resources/db/migration/V1__booking.sql) as the baseline for
a new migration. Preserve the original migration rather than rewriting history.

## 5. Refund compensation with an ambiguous result

**Failure story:** Alice's hold expires, Bob gets the seat, and Alice's delayed
payment succeeds. A refund simulator accepts the refund, but its response is
lost. The refund worker restarts and must resolve the same refund operation.

**Current behavior:** late success records REFUND_REQUIRED. The current `refund`
method changes that field to REFUNDED_SIMULATED with an audit entry. It does not
implement a provider refund or a recoverable refund-intent workflow.

**Add:** durable refund intent and immutable operation key, amount/currency binding,
separately committed simulator receipt, retry scheduling, lease ownership and
reconciliation. Distinguish requested, unknown, succeeded and terminally rejected
refund outcomes. Do not reopen Alice's seat claim while refunding her.

**Prove:** response loss and duplicate late-success callbacks leave one refund
intent and one accepted simulated refund. Restart the worker between each commit.
Bob's valid ownership remains intact throughout. Failed/refused compensation
remains visible for operational resolution instead of being marked complete.

This extends the existing acceptance/application-gap lesson into a saga: business
steps can require new compensating work after earlier steps have committed.
Start with one full refund per payment; partial refunds would introduce different
cardinality and total-refunded-amount invariants.

**Read/edit later:** `BookingService.refund`, `PaymentProcessor.apply`,
`LocalProvider` as a pattern, and the payment/receipt schema.

## 6. Database failover, stale reads and the returning old primary

**Failure story:** the primary becomes unreachable just after a hold commit.
A standby is considered for promotion; the old primary later returns. Separately,
make a replica lag while clients browse seats.

**Add in a dedicated later topology:** persistent standby storage, explicit
replication/acknowledgment policy, a promotion authority, enforceable fencing of
the old primary, a writer-routing endpoint, and bounded client pool reconnection.
Plan rejoin/failback as carefully as promotion. PostgreSQL promotion alone does
not supply the entire failure-detection/routing/fencing system.
[PostgreSQL failover](https://www.postgresql.org/docs/16/warm-standby-failover.html).

**Prove:** separate primary crash from network partition. The former writer cannot
accept authoritative writes after promotion. Replay keys reach the surviving
authority. Measure RTO from interruption to useful service and RPO by comparing
acknowledged booking IDs against recovered records. An asynchronous standby may
lack an acknowledged booking or idempotency record; replay safety cannot recover
data that did not survive. State the supported durability guarantee explicitly.

For stale reads, let browsing show an old AVAILABLE snapshot, then require holds
to consult the writer and reject conflicting ownership. Returning replicas must
catch up before serving reads that require that freshness. Test reconnect bursts
and loss of the failover controller as additional failure domains.

**Read/edit later:** `compose.yml`, datasource routing/pool configuration and
failure experiments. Preserve the current single-primary lab and retained data;
choose an isolated HA topology when implementation is selected. Two containers
on one host demonstrate mechanisms, not independent host/zone availability.

## Local reference material and what to borrow

These sources were inspected first; external official references above clarify
specific behavior. The original stacks and failure scripts were not executed.

| Local reference | Useful mechanism | Boundary found in source |
| --- | --- | --- |
| [Failover HAProxy configuration](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/failover_mechanisms/failover-demo/configs/haproxy.cfg) | Shared entry, round-robin routing and HTTP health checks | App routing does not establish database failover |
| [JavaScript circuit breaker](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Graceful_Service_Degradation/graceful-degradation-demo/src/circuit-breaker.js) | OPEN/HALF_OPEN/CLOSED teaching model | Promise timeout does not cancel the operation; no bounded half-open admission |
| [Poison-pill article](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/SDIR-pdf/systemdr-roadmap-sources/172-the-poison-pill-request-how-one-bad.html) and [Python demo](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/poison%20pill/poison-pill-demo/app/app.py) | Repeated bad work and cross-replica retry amplification | Demo deliberately terminates its process; tracking is process-local; depth checks follow JSON parsing |
| [Database orchestrator](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/database_failover/database-failover-demo/orchestrator/orchestrator.py) and [Compose](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/database_failover/database-failover-demo/docker-compose.yml) | Replication inspection and `pg_promote()` sequence | In-memory lease flag does not fence the old DB; replica storage is tmpfs; routing/rejoin are incomplete |

Do not copy the poison-pill article's universal load-balancer retry-default or
HTTP-503-is-non-retryable claims: configure and verify the chosen client's/proxy's
actual policy. Its incident numbers are not evidence about this application.
Likewise, a failover dashboard counter does not prove split-brain prevention.

## Choose one measurable addition at a time

The learner selected1–3 one at a time. API failover and draining is the current
completed delivery alongside provider isolation; poison-job handling follows separately.
Each guide must combine detailed, accessible study with3–5 interview points per
scenario/subtopic. Database HA remains a later proposal.

Each selected addition should include a normal trace, one precisely placed failure,
finite test workload, invariant assertions, attempt/backlog/latency observations,
recovery behavior, and updated API/Postman/docs where applicable. Record pass/fail
against the stated scope rather than treating an HTTP 200 or healthy container as
proof of recovery. The original suggestion session ran no experiments; scenario1's
later execution is recorded separately in verification.
