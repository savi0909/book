# Copied mock payment gateway

This standalone Java 21 / Spring Boot 3.5.5 module was copied on 2026-10-18
from `D:/customer-api/mock-payment-gateway` at the user's request. The original
project was not edited. The copy initially included its `src`, `pom.xml`, and
`Dockerfile` (73 files). This is a simulated provider, with no real charges.

The booking saga calls this provider through HTTP RPC. **Kafka remains the only
messaging system** in the booking workflow; this module adds no broker or saga
webhooks. Existing mock checkout, wallet, and legacy APIs remain available for
ordinary records. Keep these development utilities on the private Docker network
or loopback; they are not a production authentication boundary.

## Saga operation contract

Both `POST /mock-pg/saga/operations/{operationUUID}` and `GET` on the same path
require `Authorization: Bearer <MOCK_PG_SAGA_TOKEN>`. Blank server configuration
refuses requests with 503; missing/wrong credentials return 401. GET returns 404
when no committed operation exists.

Capture request:

```json
{"orderId":"9d1fe502-56ea-4b1c-b3e6-e44e4e33e5f8","operationType":"CAPTURE","amountMinor":25000,"currency":"INR"}
```

Refund request uses a new operation UUID and adds `captureOperationId` containing
the original capture UUID. It must match that capture's order, complete amount,
and currency. Values are integer **paise**; the adapter passes them unchanged to
the existing services. Some copied comments say rupees, but the schema and this
adapter use minor units.

Initial success and identical retries return HTTP 200 with the stable response:

```json
{"operationId":"41c3180a-1ad8-46b4-b59c-29acaea0cd2a","orderId":"9d1fe502-56ea-4b1c-b3e6-e44e4e33e5f8","operationType":"CAPTURE","amountMinor":25000,"currency":"INR","status":"CAPTURED","providerId":"pay_mock_example"}
```

Capture statuses are `CAPTURED`, `DECLINED`, or `ACTION_REQUIRED`; refund statuses
are `REFUNDED` or `REJECTED`. Provider IDs refer to real rows in the copied mock
payment/refund tables, and are null for a refund rejected before provider work.
Changed payload under the same UUID, a different UUID for the same order/type,
or a mismatched refund amount returns 409. Reuse the original operation UUID
after timeouts; GET reconciles a lost HTTP response. A 404 establishes only that
no result has committed yet and is not proof that an in-flight POST cannot commit.

Each order has at most one capture and one refund operation, including failures.
Operation rows, hashes, and outcomes are immutable. PostgreSQL transaction locks
serialize operation UUIDs and order UUIDs across gateway replicas. The operation
insert and existing service effects commit in one transaction. A failed commit
leaves neither a charge/refund nor an operation result.

The existing magic endings apply to the minor-unit integer: 99/88/77 decline;
55 requires action; 45 also requires action **without partial capture** for saga
traffic; 11 makes a refund fail (`REJECTED`). Other existing webhook simulation
endings still capture immediately, with no callbacks for saga traffic. Saga
origin notes suppress dispatch in the services and dispatcher. Legacy HTTP
mutation routes refuse saga records so that the stored result remains authoritative.

## Configuration and verification

| Variable | Default / use |
| --- | --- |
| `MOCK_PG_DB_URL` | `jdbc:postgresql://localhost:5553/booking?currentSchema=payment_processor,public` |
| `MOCK_PG_DB_USERNAME` | `booking_demo` |
| `MOCK_PG_DB_PASSWORD` | `booking_demo` |
| `MOCK_PG_SAGA_TOKEN` | blank; must be explicitly configured and match `ORDER_PROVIDER_TOKEN` in the booking service |

The module uses the separate `payment_processor` schema and its own Flyway
history there. V066 and V068 remain byte-for-byte copied. Only V069 adds adapter
data. The Docker build omits the destructive Maven lifecycle phase. Docker health
checks use `GET /mock-pg/health`, which checks database connectivity. Port is 8095.

Run from this directory, with Docker available:

```powershell
mvn -B -ntp verify '-Dapi.version=1.44'
```

The property selects a supported Docker API version for Testcontainers 1.21.3
on this PC's Docker 29. Tests use an isolated PostgreSQL container, never the
retained booking database. Tests cover stable HTTP replay/reconciliation, payload
conflicts, concurrent capture/refund, authorization/partial amounts, refund
validation/failure, legacy mutation guards, bearer refusal, and rollback of
legacy effects when the operation insert fails. No sustained load is run.

Copied migration SHA-256 provenance:

```text
V066 BA41E7BCAFAF66CF3CA754E9D332B60BE86BC51B782E152FF3652185988DDD18
V068 CD168885FF429A016B04A05642F15D1BD65F196BBA1AC4B798F7D84D57498EDB
```
