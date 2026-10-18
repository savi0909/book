# Order saga verification — 2026-10-18

[Learning index](TUTORIAL_INDEX.md) · [Tutorial](ORDER_SAGA_TUTORIAL.md) ·
[Runbook](ORDER_SAGA_RUNBOOK.md) · [Plan](ORDER_SAGA_PLAN.md)

The user selected the implementation brief, Kafka-only messaging, the copied
mock gateway, and refreshed learning guides. Verification used isolated
Testcontainers PostgreSQL/Kafka and a separate gateway test process. The retained
application stack was not deployed or used for bookings/load.

## Executed checks

| Check | Command | Result |
| --- | --- | --- |
| Full booking application verification and executable jar | `mvn -B -ntp verify` | **101 tests**, zero failures/errors/skips; BUILD SUCCESS at 13:48:38 IST |
| Copied gateway after normalized-path auth fix | `mvn -B -ntp test '-Dapi.version=1.44'` in `mock-payment-gateway` | **21 tests**, zero failures/errors/skips; BUILD SUCCESS at 13:45:34 IST |
| Gateway executable packaging/integration | Root Kafka integration test builds the independent provider with `-DskipTests package` | Build and separate Java process succeeded; real Kafka/provider confirmation and refund passed |
| Optional Compose combination | `docker compose -f compose.yml -f compose.failover.yml -f compose.order-saga.yml config --quiet` with temporary non-secret configuration-check token values | Valid configuration; no startup/deployment |
| SQL claim plan | `EXPLAIN (ANALYZE, BUFFERS)` with 5,000 retained published fixture rows plus ready rows | Used `order_outbox_ready_idx`; [retained plan](evidence/ORDER_SAGA_QUERY_PLAN_2026-10-18.txt) |
| Learning guide navigation | Relative file/anchor validation across changed/new Markdown | 581 local links checked across 30 files; no missing file/anchor |

The total is **122 passing tests across two independent Maven projects**. Root
verification includes 24 `OrderSagaIntegrationTest` cases and two
`OrderKafkaIntegrationTest` cases. The provider suite includes 19 integration
cases, including parameterized route variants, plus two filter unit tests.
Historical test counts in earlier project documents keep their original dates.

Machine-readable counts are in [evidence](evidence/ORDER_SAGA_2026-10-18.json).
Full local Maven logs and Surefire reports remain under each project's ignored
`target` directory. Expected warnings include JVM test-agent attachment and
cached Spring contexts observing stopped test containers; neither caused a test
failure. No Maven `clean`, filesystem deletion, retained-data reset, or sustained
workload was performed.

## Behaviors exercised

| Boundary | Executed evidence |
| --- | --- |
| API acceptance | Stored 202 replay, server price, sorted seat selection, changed-payload 409 and owner-scoped 404 |
| Cross-replica canonicalization | Opposite map-order serializers reproduce the previous false 409; fixed request-record serialization replays successfully |
| Transaction atomicity | Injected pre-commit failure leaves neither order nor request; provider operation-insert failures roll back legacy charge/refund effects |
| Same-key concurrency | Eight concurrent callers create one order with one stored response |
| Inventory exclusivity | Concurrent overlapping groups produce one confirmed order and one failed order; shared movie locks prevent overselling |
| Saga success | Durable reserve, capture, inventory commit and exactly one source confirmation event; payment alone leaves PENDING |
| Cancellation | Cancellation before reserve/capture, late capture refund, and commit before its reply; committed inventory uses CancelBooking |
| Financial compensation | Charge followed by expired reservation refunds and clears seats before FAILED; permanently rejected refund remains MANUAL_REVIEW |
| Late facts | Duplicate operation under a new message ID, stale UNKNOWN, and late COMMITTED after a newer release do not revive completed cleanup |
| Provider crash window | A committed provider receipt followed by a missing local result reconciles the original operation UUID without a second charge |
| Recovery | Finite ACTION_REQUIRED attempts, terminal next-action clearing, expiry progress beyond the first 20 rows, audited resume generations |
| Manual-review safety | Resume before queued compensation and delayed commit replies retains durable failure intent and settles as FAILED, never CONFIRMED |
| Relay ownership | Competing claimers, 100 added rows abandoned after claim, bounded expired-lease recovery, stale token heartbeat/ack/failure rejection |
| Retry and replay | Jitter timing bound, final claim becomes DEAD without failing the order, and audited same-identity replay retaining payload |
| Kafka transport | Real producer acknowledgments, duplicate publication, manual-offset consumption, durable malformed/null quarantine and valid work after poison on the same partition |
| Copied gateway integration | Real Kafka + separate copied gateway process confirm an order and refund a captured pending cancellation |
| Legacy boundary | Direct legacy service checkout/cancel rejects saga-owned bookings under lock; original movie booking tests remain green |
| Gateway authentication | Missing/wrong credentials rejected on routed matrix/encoded path variants; valid credentials work; non-routed literal encoded-semicolon forms remain 404 |

The tests model specific crash/ack windows using durable seeded state and
duplicate publication. They do not claim exhaustive operating-system process-kill
injection at every possible instruction or a measured capacity/SLO.

## Review findings and fixes

An independent scoped review identified malformed-null quarantine, unlimited
customer-action reconciliation, stale inventory facts and expiry starvation.
Focused regressions reproduce the failures and pass after their fixes.

The final independent audit requested three fixes. Each was reproduced before
its implementation change and is covered by the final passing suites:

1. Canonical API request hashing no longer serializes an unordered `Map.of`.
2. The provider filter uses Spring parsed-path matching rather than a raw URI
   prefix, matching the controller's matrix/encoded segment semantics.
3. Entering manual review persists failure intent before compensation, so an
   operator resume cannot restart forward confirmation while cleanup is queued.

Transaction-level legacy ownership guards were also reproduced RED→GREEN.
The final review found no additional blocker in leases, offsets, participant
identities, migrations or the optional Compose volume path. Existing image
metadata confirms the Kafka data path is owned/writable for its configured user;
the full optional overlay has only been configuration-validated.

## Explicit limits and operating decisions

- Kafka is the only messaging system. Provider HTTP is request/reply RPC, not a
  second broker. The original `D:/customer-api` module is untouched.
- Order/Inventory/Payment are logical participants in the booking process and
  database; the provider is a separate process/schema. This is not independent
  database availability or broker HA.
- The API uses a development trusted-caller token and asserted customer UUID,
  not production customer login. Provider and operator tokens are separate.
- The copied gateway performs simulated capture/refund. Customer-action
  completion, real provider webhooks and real payment integration are not delivered.
- Confirmed-order cancellation needs a separate ticket/refund policy and is
  rejected. Pending cancellation has the tested compensation path.
- No automatic retention deletion, existing-booking backfill, notification
  consumer, remote Ankita execution, Redis, new loader or sustained load is delivered.
- Saga-linked shows are retained even after terminal orders. Cleanup remains a
  separately reviewed future action, preserving deduplication and obligations.
- Existing V1–V7 and copied provider V066/V068 are preserved. V8 and V069 are new.

The guides distinguish these implemented reference guarantees from future
production capabilities. Start learning at the [tutorial index](TUTORIAL_INDEX.md),
then follow one lesson's checkpoint before advancing.
