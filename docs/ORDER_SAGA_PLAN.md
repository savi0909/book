# Order outbox + saga implementation plan

Date: 2026-10-18. Branch: `feature/order-outbox-saga`.
Source: user-selected [order-service-outbox-saga brief](ORDER_SAGA_BRIEF.md), copied
from the local ChatGPT project artifact.
User correction: **Kafka only** for messaging.

## Design decisions

Keep Java 21, Boot 3.5.16, JDBC/PostgreSQL and Flyway. Add an optional,
default-off `/api/orders` workflow; preserve existing booking endpoints.
No broker exists today. Add Kafka, using order ID keys, acknowledged producers,
manual consumer offset commits after local transactions, durable inboxes and
quarantine. No distributed exactly-once claim.

Inventory wraps the movie seat state machine, using sorted seat locks and
durable command tombstones. Saga-created bookings are protected from automatic
catalog purge. Payment is a durable simulated provider reference participant,
with separate operation/provider transactions and stable capture/refund keys.
User additionally selected the existing mock gateway from
`D:/customer-api/mock-payment-gateway`; its source/POM/Dockerfile are copied into
this repo and extended through V069 with atomic, immutable operation results.
This does not integrate a real processor or authenticate real customers.
The reference API uses an explicitly configured shared bearer token plus a
customer UUID header; replace this trust boundary before external deployment.

Flow: reserve -> capture -> commit -> confirm. Cancellation of pending orders
persists intent, cancels the original capture, releases the original reservation,
and refunds settled funds. A committed reservation uses CancelBooking. Confirmed
order cancellation is rejected. Unknown outcomes trigger status queries and
bounded durable recovery, then manual review with obligations retained.

## Tasks and verification

1. Add PostgreSQL/API integration tests first. Initially POST must fail (404).
2. Add V8 only: order/request/saga/command, outbox/inbox/quarantine/audit,
   inventory reservation and payment operation tables; provider storage lives
   in the copied gateway's own schema/migration.
   State constraints, lease ownership constraints and partial due indexes.
3. Implement atomic keyed order creation, server price snapshot, owner-scoped
   reads/cancel, guarded replies and compensations. Test rollback/concurrent
   replay/changed payload, all happy phases and cancellation late facts.
4. Implement claim/publish/complete, bounded expired lease recovery, jitter,
   DEAD and audited same-identity replay. Test competing claimers, abandoned
   100-row batch, stale token completion/heartbeat/failure fencing.
5. Implement Inventory and Payment participants with inbox/reply-outbox and
   persisted tombstones/operations. Test seat races, expiry vs commit, decline,
   unknown/late charge, lost reply, refund/release convergence and restart gaps.
6. Add Kafka adapter and isolated Kafka integration coverage, default-off
   configuration/optional Compose, metrics and recovery runbook. Test duplicate
   delivery and invalid-message quarantine using Kafka and PostgreSQL.
7. Run project Maven verify without clean and review the final diff. Record
   exact results and limitations in status/context/worklog and runbook.

The selected brief explicitly requests correctness tests. No sustained load,
retained application startup, production provider, deployment or merge is selected.
Never delete files/directories or run clean/reset/prune. Existing V1–V7 unchanged.

## Review focus

Cancellation tombstones must precede delayed work safely. Unknown status cannot
prove a queued command will not run. Late facts still require compensation.
Outbox DEAD is independent of business outcome. Token fencing includes expiry.
Participant operations deduplicate independently of message IDs. Kafka offset
failure after DB commit must safely redeliver. Catalog retention must not strand
saga reservations or erase reconciliation evidence.

## Progress

- Inspection complete; existing movie refund flag alone cannot fulfill Payment.
- Branch created; Kafka correction incorporated before implementation.
- Implementation and documentation delivered. Initial POST test failed 404,
  then passed; concurrency, compensation, expiry and replay tests added.
- Independent scoped review findings were reproduced and fixed: Kafka tombstone
  quarantine, finite customer-action reconciliation, stale inventory facts and
  expiry batch progress. Transaction-level legacy ownership guard also RED→GREEN.
- Kafka/PostgreSQL/copied-gateway tests run as isolated correctness fixtures;
  production-like Compose topology is configuration-validated, not deployed.
- Final validation and remaining operational boundaries are recorded in
  [verification](ORDER_SAGA_VERIFICATION.md).
- Final audit's cross-replica canonical hash, provider normalized-path auth and
  manual-review stop-intent findings each reproduced RED→GREEN. Final root
  `verify`: 101 passed; final gateway tests: 21 passed, zero failures/errors/skips.
- Follow-up user request completed: [tutorial index](TUTORIAL_INDEX.md), updated
  guide navigation and scope notes, focused saga review lessons and interview
  questions. 581 local links/anchors validated across 30 changed/new Markdown files.
