# Source relationship and intentional differences

## API failover extension — 2026-10-04

Inspected original failover_mechanisms/failover-demo/configs/haproxy.cfg and
src/services/server.js in the reference tree. Reused the shared-entry/health-check
lesson, not its product/order contract. Original SIGTERM handling ends DB/Redis
clients and exits without explicit HTTP listener drain; Java uses Spring graceful
shutdown, atomic lifecycle admission and observable in-flight completion. No
Redis dependency or unsafe generic POST replay was imported. The original source
was not executed. Optional HAProxy and deterministic response-delay controls have
their own real-process verification; this does not establish original API parity
or database failover. See [tutorial](docs/API_FAILOVER_TUTORIAL.md).

This is a **new ticket-booking API contract**. No original ticket-booking topic was
found in the reference index/tree during discovery. Related locking/payment demos
are conceptual sources, not interchangeable baselines. No direct original-to-Java
HTTP, dashboard, performance or test parity is claimed.

Reference repository:
`D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main`.

| Inspected source | Observed mechanism | Booking design / evidence |
| --- | --- | --- |
| [locking backend](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Optimistic_locking_vs_Pessimistic_locking/locking-demo/backend/server.js) | Account balance transaction uses SELECT FOR UPDATE; alternative reads version then conditional update/retry | Seat-first short transactions and active-seat index; PostgreSQL races and constraint bypass test |
| [locking tests](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Optimistic_locking_vs_Pessimistic_locking/locking-demo/backend/tests/test.js) | Account reset/batches, console.assert checks | New failing assertions for inventory/state races; original tests not run |
| [payment gateway](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Designing_for_Global_Payment_Systems/global-payment-system/services/gateway/server.js) | Redis GET then processor call then SETEX for response deduplication; static rate table | Persisted hold/checkout request identity, changed-input rejection, no currencies/provider calls |
| [payment processor](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Designing_for_Global_Payment_Systems/global-payment-system/services/processor/server.js) | Unique payment key, transaction, randomized outcomes; metrics/pubsub after commit | Deterministic local receipt, lease recovery, event deduplication and durable booking/refund audit |
| [payment tests](D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main/Designing_for_Global_Payment_Systems/global-payment-system/tests/test.js) | Sequential duplicate/rate/fraud/state checks; some failures only logged | New real-DB and cross-replica assertions; no original-suite equivalence |

Also inspected the related Compose definitions, locking manifest/top-level tests
and payment README. Locking startup drops/recreates the account table; its reset
and random sleeps do not belong in a retained booking system. Payment's Redis
check-before-process window is not atomic request ownership. A concurrent duplicate
can reach the unique database constraint before a cached result exists. Payment
state transitions are declared but its processing path directly assigns stages;
post-commit metrics/pubsub can diverge on failures. The README's production/ML/global
claims are not established by the random/static single-processor source.

Original scripts and stacks were not run: they do not expose booking contracts,
locking initialization is destructive to its demo data, and setup/cleanup commands
are outside this scoped new lab. All original sources and earlier Java projects
are preserved.

Intentional new choices: one seat/booking; no existing dashboard/stream protocol;
JSON APIs and Postman teach the state machine. PostgreSQL clock controls expiry,
all inventory transitions share lock order, request/receipt/event records persist,
payment intent commits before simulation, success after expiry/cancellation needs
refund reconciliation. No Redis, actual providers, authentication, fraud scoring,
exchange-rate conversion or multi-region behavior is implemented. See
[spec](docs/SYSTEM_SPEC.md), [API](docs/API_REFERENCE.md) and
[verification](docs/VERIFICATION.md) for the actual boundary and executed evidence.
