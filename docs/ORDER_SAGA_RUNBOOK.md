# Order saga runbook

[Tutorial index and learning guide](TUTORIAL_INDEX.md) · One lesson at a time; runtime exercises require a selected hands-on session.

Date: 2026-10-18. Scope: the optional `/api/orders` reference workflow. Read the
[tutorial](ORDER_SAGA_TUTORIAL.md) for the source walkthrough and
[verification record](ORDER_SAGA_VERIFICATION.md) for executed checks. Commands
below are operating instructions, not a record that the retained stack was started.
Only isolated correctness tests were selected during implementation; no sustained
load, retained-stack startup, deployment, or production payment was performed.

## What runs and what stays separate

`ORDER_SAGA_ENABLED` defaults to `false`. With it off, the new controller, workers,
Kafka adapter and order metrics are disabled. Flyway V8 still runs when the new
application starts: the flag controls the workflow, not schema migration.
Existing generic booking and movie APIs coexist with this API. They are not
automatically routed through a saga or backfilled into `orders`.

The booking API process contains logical Order, Inventory and Payment participants.
They use separate local transactions against the same booking PostgreSQL database.
The copied mock gateway is a separate Java process with its own `payment_processor`
schema and Flyway history in that same database. This demonstrates durable protocol
boundaries; it does not demonstrate independent database availability.

The provider was copied from `D:/customer-api/mock-payment-gateway`; that source
project was not edited. See its [README](../mock-payment-gateway/README.md) for
the HTTP contract and simulated outcomes. Kafka is the only messaging system.
Payment calls the gateway through HTTP; no saga webhook or second broker is used.

| Local endpoint | Purpose |
| --- | --- |
| `127.0.0.1:8130`, `:8131` | API replicas A and B |
| `127.0.0.1:8132` | Optional existing failover gateway |
| `127.0.0.1:5553` | Booking PostgreSQL |
| `127.0.0.1:9094` | Optional Kafka host listener; Docker clients use `kafka:9092` |
| `127.0.0.1:8136` | Copied mock payment gateway; Docker clients use port 8095 |

The optional Kafka broker is a single KRaft node, three partitions per topic,
replication factor one, and plaintext loopback/private-network traffic. It provides
no broker HA or production transport security. `acks=all` cannot manufacture a
second replica. PostgreSQL remains a shared failure domain.

## Authentication and API contract

Choose separate secrets for these three purposes:

| Configuration | Who uses it |
| --- | --- |
| `ORDER_API_TOKEN` | Trusted development caller of `/api/orders` |
| `ORDER_PROVIDER_TOKEN` | Booking Payment participant; the overlay passes the same value as gateway `MOCK_PG_SAGA_TOKEN` |
| `ORDER_OPERATOR_TOKEN` | Operator recovery controls, when explicitly enabled |

Order calls require `Authorization: Bearer <ORDER_API_TOKEN>` and UUID
`X-Customer-Id`. The shared token authorizes a trusted caller to assert that header;
it does not authenticate a real customer. Owner checks prevent reads/cancels under
a different header value, but a holder of the shared token can assert either
identity. Replace this boundary before exposing the workflow externally. Blank
configured API/operator/provider credentials fail closed for their protected paths.
Legacy endpoints retain their own existing contracts.

| Method and path | Request/result |
| --- | --- |
| `POST /api/orders` | `Idempotency-Key`, body `{"showId":"<uuid>","seatNumbers":[1,2]}`; `202` with `orderId` and `statusUrl` |
| `GET /api/orders/{id}` | Owner-scoped status, saga phase, inventory/payment facts, server amount, next action and error |
| `POST /api/orders/{id}/cancel` | Owner-scoped pending-order cancellation; `202`; cleanup is asynchronous |

The server sorts 1–10 distinct seat numbers, looks up their prices and saves the
seat selection, total minor-unit amount and INR currency. The caller cannot set
the price. The hold rechecks the amount; a changed price is rejected rather than
charging a different amount. A matching customer/key/request returns the stored
`202` response even after the order progresses. Changed payload under that key
returns `409 IDEMPOTENCY_CONFLICT`. A wrong owner gets `404`. Cancellation after
`CONFIRMED` returns `409 CONFIRMED_CANCELLATION_UNSUPPORTED`; no ticket/refund
policy for confirmed-order cancellation is implemented.

## Configuration

Defaults come from [application.yml](../src/main/resources/application.yml).
Both API replicas must use the same Kafka group and compatible message schema.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ORDER_SAGA_ENABLED` | `false` | Enable the new API/workflow |
| `ORDER_SAGA_WORKER_ENABLED` | `true` | Scheduled work, gated by saga enablement |
| `ORDER_KAFKA_ENABLED` | `true` | Kafka adapter, gated by saga enablement |
| `ORDER_KAFKA_BOOTSTRAP` | `localhost:9094` | Overlay changes this to `kafka:9092` |
| `ORDER_KAFKA_GROUP` | `booking-saga-v1` | Shared subscription to Inventory, Payment and reply topics |
| `ORDER_API_TOKEN`, `ORDER_PROVIDER_TOKEN` | blank | Explicit credentials required |
| `ORDER_PROVIDER_URL` | `http://localhost:8136` | Overlay uses `http://mock-payment-gateway:8095` |
| `ORDER_CONTROLS_ENABLED` | `false` | Enable replica-local recovery controller |
| `ORDER_OPERATOR_TOKEN` | blank | Distinct operator credential |
| `ORDER_POLL_MS` | `500` | Delay between completed worker ticks |
| `ORDER_BATCH_SIZE` | `20` | Relay batch ceiling, allowed 1–100 |
| `ORDER_PUBLISH_CONCURRENCY` | `10` | Simultaneous relay sends, allowed 1–32 |
| `ORDER_LEASE_SECONDS` | `30` | Outbox lease duration |
| `ORDER_BROKER_TIMEOUT_MS` | `5000` | Producer and offset-operation bound |
| `ORDER_MAX_CLAIMS` | `10` | Outbox delivery budget |
| `ORDER_BACKOFF_BASE_MS`, `ORDER_BACKOFF_CAP_MS` | `1000`, `300000` | Full-jitter exponential delivery retry |
| `ORDER_DEADLINE_SECONDS` | `120` | Saga business deadline, followed by compensation |
| `ORDER_STEP_SECONDS` | `10` | Durable saga query/recovery interval |
| `ORDER_MAX_RECOVERY` | `10` | Automatic saga recovery budget |
| `ORDER_MAX_PAYMENT_ATTEMPTS` | `10` | Provider reconciliation budget |

Claims are bounded by batch size, concurrency and available capacity. Lease
validation requires `lease_ms >= 2 * broker_timeout_ms + 5000`. Increasing batch
size alone does not increase the default ten simultaneous sends. Payment work
claims at most ten operations per tick, with 15-second fenced leases, two-second
connect and three-second read timeouts. Unresolved operations retry after two
seconds. These are reference defaults, not measured production capacity.

## Build and optional startup — PowerShell

Prerequisites: Java 21, Maven, Node for the existing root test suite, and Docker
for Testcontainers/Compose. Never run Maven `clean`, reset/prune commands, or delete
retained data. Run root and provider Maven commands sequentially.

```powershell
Set-Location 'D:\sd-book-my-show'
mvn -B -ntp verify
if ($LASTEXITCODE -ne 0) { throw 'Booking verification failed' }
Push-Location '.\mock-payment-gateway'
try {
    mvn -B -ntp verify '-Dapi.version=1.44'
    if ($LASTEXITCODE -ne 0) { throw 'Gateway verification failed' }
} finally {
    Pop-Location
}
```

The provider property accommodates Testcontainers on this Windows Docker setup.
The root Dockerfile consumes `target/sd-book-my-show-1.0.0.jar`; it does not build
the jar. Provider Docker build packages its own jar without `clean`.

Set user-chosen secrets in the current PowerShell session before inspecting the
optional overlay. Replace all placeholders; do not commit real values:

```powershell
$env:ORDER_API_TOKEN = '<choose-a-private-caller-secret>'
$env:ORDER_PROVIDER_TOKEN = '<choose-a-different-private-provider-secret>'
$sagaCompose = @('-f', 'compose.yml', '-f', 'compose.failover.yml', '-f', 'compose.order-saga.yml')
docker compose @sagaCompose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid Compose configuration' }
docker compose @sagaCompose build api-a api-b mock-payment-gateway
if ($LASTEXITCODE -ne 0) { throw 'Container build failed' }
```

After selecting a rollout window, drain/stop **both old API binaries before the
new V8-aware writer is started**. An old schedule maintainer does not know the
saga retention rule and must not run beside new saga bookings. Do not mix versions
by replacing one replica while the other old replica keeps serving maintenance.
V1–V7 are preserved; V8 adds the order tables. The provider applies its separate
adapter migration. Existing movie bookings receive no automatic saga backfill.

The following commands stop the existing APIs/gateway and then start the optional
stack. They are a future operating procedure, not actions performed for this doc:

```powershell
docker compose @sagaCompose stop gateway api-a api-b
if ($LASTEXITCODE -ne 0) { throw 'Could not drain the old API processes' }
docker compose @sagaCompose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Saga stack did not become healthy' }
docker compose @sagaCompose ps
Invoke-RestMethod 'http://127.0.0.1:8130/actuator/health/readiness'
Invoke-RestMethod 'http://127.0.0.1:8136/mock-pg/health'
```

The failover overlay also enables its existing failover demo controls. HAProxy
blocks `/api/demo/order-saga` along with other replica controls. Normal
`/api/orders` traffic can pass through it. No broker topic deletion, volume
deletion or service-data reset is needed for deployment or recovery.

For a bounded manual API check after an explicitly selected startup, choose a
current show and available seats through the existing movie browsing API:

```powershell
$customerId = [guid]::NewGuid().ToString()
$showId = '<existing-open-show-uuid>'
$requestKey = [guid]::NewGuid().ToString()
$headers = @{ Authorization = "Bearer $env:ORDER_API_TOKEN"; 'X-Customer-Id' = $customerId; 'Idempotency-Key' = $requestKey }
$body = @{ showId = $showId; seatNumbers = @(1, 2) } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8132/api/orders' -Headers $headers -ContentType 'application/json' -Body $body
$orderId = $created.orderId
Invoke-RestMethod -Uri "http://127.0.0.1:8132/api/orders/$orderId" -Headers $headers
```

Repeat the POST with the same headers/body to inspect API replay; poll explicitly
as needed rather than generating a sustained workload. A `202` means durable
acceptance, not confirmed seats or settled funds.

## Delivery and observability

The four topics are `booking.inventory.commands.v1`,
`booking.payment.commands.v1`, `booking.order.replies.v1` and
`booking.order.events.v1`. Producers use the order UUID as key, wait for an
acknowledgment, and enable Kafka producer idempotence. The shared
`booking-saga-v1` consumer group subscribes to the first three topics; event
consumers are future integrations and need their own groups and inboxes.
An order key does not impose global order across topics or concurrent relays.

Consumers commit offsets manually only after the business transaction, including
inbox/effects/reply outbox, commits. A failed offset commit causes redelivery.
Invalid envelopes/keys are durably quarantined immediately; other processing
failures quarantine on the fifth recorded failure. Until failure persistence or
business processing commits, the partition seeks back and does not advance over
the record. A durable quarantine allows offset progress, leaving an operator
obligation in `order_message_failures`.

Read-only inspection (run against the selected stack):

```powershell
$inspectSql = @'
SELECT id,status,amount_minor,currency,updated_at FROM orders ORDER BY created_at DESC LIMIT 20;
SELECT order_id,phase,inventory_state,payment_state,cancel_requested,failure_requested,recovery_attempts,next_action_at,last_error FROM order_sagas ORDER BY updated_at DESC LIMIT 20;
SELECT status,count(*),min(created_at) AS oldest_created FROM order_outbox GROUP BY status;
SELECT id,aggregate_id,event_type,status,attempts,locked_until,last_error FROM order_outbox WHERE status='DEAD' OR (status='IN_PROGRESS' AND locked_until<=clock_timestamp()) ORDER BY updated_at LIMIT 50;
SELECT order_id,reserve_command_id,booking_id,state,stop_requested,version FROM order_inventory ORDER BY updated_at DESC LIMIT 20;
SELECT id,order_id,operation_type,provider_key,status,attempts,stop_requested,locked_until FROM order_payment_operations ORDER BY updated_at DESC LIMIT 20;
SELECT topic,partition_id,offset_id,attempts,quarantined,last_error FROM order_message_failures ORDER BY updated_at DESC LIMIT 20;
SELECT * FROM order_recovery_audit ORDER BY id DESC LIMIT 20;
'@
$inspectSql | docker compose @sagaCompose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U booking_demo -d booking
docker compose @sagaCompose logs --tail 100 api-a api-b mock-payment-gateway kafka
docker compose @sagaCompose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group booking-saga-v1
```

`OrderMetrics` registers `booking.order.outbox.pending`, `.outbox.dead`,
`.outbox.expired.leases`, `.outbox.publish.errors`, `.outbox.oldest.seconds`,
`.sagas.overdue`, `.sagas.manual.review`, `.payments.unresolved` and
`.messages.quarantined`. These gauges query the DB; publish errors count rows
currently carrying an error, not a lifetime error counter. DB failure yields NaN.
Only Actuator `health,info` are exposed by default. To inspect metrics, explicitly
set `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics` in a local
launch or local Compose override for an API replica, recreate that replica, then
use its loopback `/actuator/metrics/booking.order.outbox.pending`. A host variable
alone is not forwarded by the existing overlay. Keep metrics access local; no
Prometheus scrape endpoint, alert deployment or external exposure is implied.

## Recovery procedures

First identify order, saga, command, outbox message and provider operation UUIDs.
`DEAD` means delivery exhausted; it does not prove the participant failed.
`PUBLISHED` proves acknowledged publication, not completed business work. Query
participant facts and provider state before deciding what to replay.

1. **Broker/DB outage or expired relay lease:** repair connectivity/configuration;
   workers recover expired claims in bounded batches. The same message may have
   reached Kafka before the lease was lost. Inbox and business identities absorb
   duplicates. Do not change message ID/payload to get around a failed delivery.
2. **DEAD outbox row:** inspect immutable payload and cause, repair the cause,
   then use the audited replay API below. It resets only that row's delivery
   budget and keeps its identity. Invalid payload requires code/contract repair;
   replaying unchanged invalid data does not repair it.
3. **UNKNOWN/ACTION_REQUIRED payment:** query the gateway by the persisted
   `order_payment_operations.id` with the provider token. `404` only means no
   committed operation was visible; an in-flight POST may still commit. The
   participant retries GET, then POST only under that same UUID/amount/currency.
   Never create a new charge key or mutate inbox/operation facts to force progress.
4. **Payment MANUAL_REVIEW:** inspect capture and refund, including provider
   outcome; fix provider/configuration failure. Schedule the payment reconcile
   control first, then resume the saga if its phase is MANUAL_REVIEW. A rejected
   immutable mock refund will remain rejected; repeating it cannot simulate a
   successful financial repair. Preserve the obligation for an operator decision.
5. **Saga MANUAL_REVIEW:** inspect inventory ownership and authoritative capture/
   refund state, not just order status. Resume uses new query command generations,
   retains original reserve/capture/refund identities and preserves stop intent.
   Entering manual review persists failure intent before scheduling compensation;
   resume retains it and cannot return that workflow to forward confirmation.
   An expired deadline still leads to compensation. Resume cannot invent success.
6. **Quarantined message:** retain topic/partition/offset/payload/error and identify
   the affected order. Repair the handler/contract and record an incident audit.
   There is no quarantine replay endpoint. An approved, separately audited Kafka
   tool may republish the exact original envelope with the same message UUID and
   order key after repair. Do not edit `order_inbox`, rewrite the original payload,
   reset the whole consumer group, or invent a new payment operation. A malformed
   original payload cannot be fixed by identical replay: use supported saga
   reconciliation or implement a reviewed recovery path. Quarantine is not proof
   that the financial/inventory obligation disappeared.

Controls require both `ORDER_SAGA_ENABLED=true` and
`ORDER_CONTROLS_ENABLED=true`, plus a distinct `ORDER_OPERATOR_TOKEN`. These are
replica-only paths; HAProxy returns 404 for them. The optional overlay intentionally
does not enable or forward these operator settings. For a selected recovery
window, use a private local override, for example:

```powershell
$env:ORDER_OPERATOR_TOKEN = '<choose-a-third-private-operator-secret>'
@'
services:
  api-a:
    environment:
      ORDER_CONTROLS_ENABLED: "true"
      ORDER_OPERATOR_TOKEN: ${ORDER_OPERATOR_TOKEN:?Set ORDER_OPERATOR_TOKEN}
'@ | Set-Content -LiteralPath '.\compose.order-saga.operator.local.yml' -Encoding utf8
$operatorCompose = $sagaCompose + @('-f', 'compose.order-saga.operator.local.yml')
docker compose @operatorCompose up -d --no-deps --wait api-a
$operatorHeaders = @{ Authorization = "Bearer $env:ORDER_OPERATOR_TOKEN" }
$auditBody = @{ actor = '<operator-name>'; reason = '<incident-and-reviewed-recovery-reason>' } | ConvertTo-Json
$eventId = '<dead-outbox-message-uuid>'
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8130/api/demo/order-saga/outbox/$eventId/replay" -Headers $operatorHeaders -ContentType 'application/json' -Body $auditBody
```

Only call the controls that match the inspected incident:

```powershell
$operationId = '<manual-review-payment-operation-uuid>'
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8130/api/demo/order-saga/payments/$operationId/reconcile" -Headers $operatorHeaders -ContentType 'application/json' -Body $auditBody
$orderId = '<manual-review-order-uuid>'
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8130/api/demo/order-saga/orders/$orderId/resume" -Headers $operatorHeaders -ContentType 'application/json' -Body $auditBody
```

Verify provider settlement, inventory release/commit, order state, remaining
outbox work and audit rows afterward. Disable controls by recreating api-a with
the original overlay set (`docker compose @sagaCompose up -d --no-deps --wait api-a`).
Keep the local override out of commits; no deletion is required. Escalate unresolved
refunds, contradictory settled facts and malformed messages needing a new recovery
path to the operator/maintainer; never mark an order confirmed directly in SQL.

## Pause, rollback and retention

To pause all processing while retaining every container/volume, use
`docker compose @sagaCompose stop gateway api-a api-b mock-payment-gateway kafka-topics kafka`.
PostgreSQL can remain running for inspection. To disable new order admission while
processing existing work requires a separate admission policy; the current
`ORDER_SAGA_ENABLED=false` disables both API and workers. Stop admission upstream
and let obligations settle before disabling the workflow. Do not roll back to old
maintenance binaries while saga-linked bookings exist. A safe operational pause
is preferable to attempting to reverse V8 or deleting workflow rows.

The supported Kafka replay horizon is **14 days**, set by topic
`retention.ms=1209600000` in the optional topic initializer. `--if-not-exists` does
not change existing topics: inspect their actual config before relying on the
horizon. Reconciliation obligations can last longer than Kafka retention.

No automatic saga table cleanup or gateway operation cleanup is implemented.
Retain API request keys, inbox hashes, participant operations, provider results,
cancellation tombstones and recovery audits beyond the supported replay horizon,
and longer for every unresolved financial/inventory obligation. Do not replay
older traffic after removing its deduplication evidence. V8's inventory FK and
the updated movie maintainer protect saga-linked movie bookings: any show with
such a booking is excluded from the normal past-day purge, even when the saga
is terminal. This deliberately retains more catalog data.

A future cleanup policy needs explicit approval, a defined deduplication cutoff,
and reviewed bounded SQL that selects only eligible terminal work while retaining
unresolved/DEAD/quarantined evidence and required dependencies. Cleanup indexes
are preparation, not a scheduled deletion job. This delivery implements no
destructive cleanup SQL and performs no deletion.
