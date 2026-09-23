# API reference

Base URLs: `http://localhost:8105` and `http://localhost:8106`; optional failover
gateway `http://localhost:8107`. JSON request/response,
camelCase fields, UUID identifiers, ISO-8601 UTC timestamps. No authentication,
authorization, CORS enablement or caller ownership checks: these are local demo
APIs. Event creation/callback/reconciliation/refund routes under `/api/demo` are
explicit teaching controls. No real provider endpoint or payment credential exists.

| Method/path | Input | Status and behavior |
| --- | --- | --- |
| GET /health | None | 200 process status/instance, independent of DB |
| GET /actuator/health | None | Overall dependency health; 200 UP or 503 DOWN |
| GET /actuator/health/liveness | None | Framework process probe |
| GET /actuator/health/readiness | None | Includes readinessState and DB; 503 during drain or DB failure |
| GET /actuator/info | None | 200; empty info object |
| POST /api/demo/events | name, seatCount | 201 new retained fixture; no idempotency on event creation |
| GET /api/events | limit, offset | 200 array ordered newest first, UUID tie break |
| GET /api/events/{id} | UUID | 200 event or 404 |
| GET /api/events/{id}/seats | limit, offset | 200 seat array ordered numerically; no booking IDs/buyer IDs exposed in seat list |
| POST /api/holds | Hold body plus Idempotency-Key | 201 fresh, 200 replay, 409 unavailable/changed input |
| GET /api/bookings/{id} | UUID | 200 booking; materializes expiry before response |
| GET /api/bookings/{id}/audit | UUID | 200 array of first 100 audit records, oldest first |
| POST /api/bookings/{id}/checkout | Checkout body plus Idempotency-Key | 202 accepted intent, 200 replay, 409 invalid hold/changed intent |
| POST /api/bookings/{id}/cancel | No body | 200 current booking; repeated or already terminal cancellation replays |
| GET /api/payments/{id} | UUID | 200 payment record |
| POST /api/demo/payments/{id}/callback | eventId, outcome | 200 applied/replayed booking; 409 not ready, outcome mismatch or event collision; 404 absent receipt/payment |
| POST /api/demo/payments/{id}/reconcile | No body | 200 current booking after one bounded recovery attempt; may still be unresolved/leased |
| POST /api/demo/payments/{id}/refund | No body | 200 locally acknowledged refund/replay; 409 no refund required |
| GET /api/stats | None | 200 physical booking/payment counts and refundRequired; separate reads, not one atomic snapshot |

Every resource route can return 404. Invalid UUIDs, missing required headers/fields,
invalid enums/bounds/JSON yield 400. Database unavailability or lock/statement errors
yield 503 with `Retry-After: 1`. For an ambiguous hold/checkout response, reuse the same
key and input; do not infer rollback from a timeout.

Event name: nonblank, at most 80 characters. seatCount 1..200. Fixed event priceMinor
5000 and currency INR. List limit 1..100, offset 0..10000; defaults 50 events/100 seats,
offset 0. Offset pages can shift when new events are created; there is no cursor snapshot.
Buyer ID: `[A-Za-z0-9_.:-]{1,64}`. Idempotency-Key and eventId:
`[A-Za-z0-9_.:/-]{1,128}`. Hold TTL 2..120 integer seconds. Seat number 1..200 and must
exist for the event. Numeric fractions reject; Jackson's default numeric-string
coercion is retained (e.g. `"2"` may parse as integer). Extra JSON fields are ignored.

Create event:

```json
{"name":"Interview concert","seatCount":12}
```

Hold body (`Idempotency-Key: alice-seat1-v1`):

```json
{"eventId":"replace-with-event-uuid","seatNumber":1,"buyerId":"alice","ttlSeconds":120}
```

Booking shape (illustrative UUIDs/timestamps):

```json
{
  "id":"00000000-0000-0000-0000-000000000001",
  "eventId":"00000000-0000-0000-0000-000000000002",
  "seatNumber":1,
  "buyerId":"alice",
  "state":"HELD",
  "reconciliation":"NONE",
  "expiresAt":"2026-10-01T12:02:00Z",
  "createdAt":"2026-10-01T12:00:00Z",
  "payment":null,
  "replayed":false
}
```

Checkout body (`Idempotency-Key: alice-checkout-v1`):

```json
{"scenario":"SUCCESS","delayMs":0}
```

Scenario SUCCESS gives immutable success; FAILURE gives immutable failure; UNKNOWN
accepts success but initially hides the response. delayMs 0..10000 controls when a
receipt becomes observable. It does not sleep inside a transaction. The returned
booking initially has CHECKOUT and a payment containing id, bookingId, scenario,
delayMs, state, readyAt and attempts. Maintenance completes it asynchronously.
Retry responses show current state, not a cached original snapshot. One payment
per booking: a failed payment requires a new hold/booking for another checkout.
Checkout acceptance does not extend hold expiry.

Callback body:

```json
{"eventId":"provider-demo-event-1","outcome":"SUCCESS"}
```

Only SUCCESS/FAILURE callback outcomes are accepted and must match the simulator's
durable receipt. A terminal callback replay can return `replayed:true` even for a
new delivery event ID. `provider/{payment UUID}` is the internal recovery event
name; choose a distinct prefix for manual tests. REFUNDED_SIMULATED is an audit
acknowledgment only. Repeated callbacks preserve it.

Seat response: `[{"seatNumber":1,"availability":"HELD"}]`.
Values AVAILABLE/HELD/BOOKED represent a database statement snapshot; a later hold
can still conflict. Audit item: id, action, createdAt. Stats count physical states,
so sweep lag can leave expired logical holds counted as HELD/CHECKOUT temporarily.

Errors use `{ "code": "SEAT_UNAVAILABLE", "message": "Seat is already held or booked" }`.
Codes include INVALID_REQUEST, INVALID_KEY, NOT_FOUND, SEAT_UNAVAILABLE,
IDEMPOTENCY_CONFLICT, HOLD_NOT_VALID, PROVIDER_PENDING, OUTCOME_CONFLICT,
EVENT_CONFLICT, NO_REFUND_REQUIRED and DATABASE_UNAVAILABLE. Unsupported HTTP
paths/methods use Spring's default errors instead of this domain envelope.

## Optional failover controls and response timing

Enable only for the local study with `FAILURE_CONTROLS_ENABLED=true`; the optional
Compose overlay sets this on both APIs. Routes are unavailable (404) by default.
Call the individual replica port: HAProxy rejects the control path with 404.
All normal API/health responses add `X-Booking-Instance` identifying the replica.
Gateway-generated errors may be HTML and need not include that header.

| Method/path | Behavior |
| --- | --- |
| GET /api/demo/failover/status | 200 with instance, admission{draining,inFlight,stopping}, waitingBookings UUID array |
| POST /api/demo/failover/drain | Close new business/maintenance admission, withdraw readiness, return same status shape |
| POST /api/demo/failover/resume | Reopen admission/readiness on a running replica; 409 INSTANCE_STOPPING if context closing |

`inFlight` counts admitted business requests plus a maintenance tick, excluding
health/control requests. An already admitted request continues after drain; new
business routes return 503/INSTANCE_DRAINING and `Retry-After: 1`. Readiness includes
DB health, so resume is not a promise that the dependency is healthy.

Checkout additionally accepts integer header `X-Lab-Response-Delay-Ms` (default 0,
range 0..10000). Positive delay requires enabled controls. Disabled positive delay
returns 404/DEMO_CONTROL_DISABLED; out-of-range returns 400/INVALID_RESPONSE_DELAY
before checkout mutation. Valid delay happens after checkout commits and before
the response body is delivered. A waiting booking is visible in local status.
Same-key/payload replay remains mandatory after response loss; delay does not
extend hold expiry. The response represents state captured before its delay.

Controls and fault header are unauthenticated local fixtures. Gateway exclusion
of control routes is not production authentication; the fault header remains
usable through the loopback gateway for the experiment. No crash endpoint exists;
the runtime script sends scoped Docker signals. See [scenario guide](API_FAILOVER_TUTORIAL.md).
