# Movie booking APIs

JSON on A8130/B8131/gateway8132; local unauthenticated fixtures, no real payments.
Buyer IDs are labels. Amounts are integer INR minor units. V5 movie endpoints
operate separately from inherited `/api/events` and generic bookings.

| Method/path | Contract |
| --- | --- |
| POST /api/demo/movie/multiplexes |201;5-100 uniquely named screens,200-500 seats each, valid IANA zone |
| GET /api/movie/multiplexes/{id} |200; screens/category counts |
| POST /api/demo/movie/movies |201; title/language/runtime1-360 minutes |
| POST /api/demo/movie/shows |201; future timestamp, turnaround0-120 minutes; overlap409 |
| GET /api/movie/shows?multiplexId=&date=&limit=&offset= |200; local start date; limit1-100/default50, offset0-100000 |
| GET /api/movie/shows/{id} |200; movie/screen/time metadata |
| GET /api/movie/shows/{id}/seats?limit=&offset= |200; AVAILABLE/HELD/BOOKED; limit1-500/default500, offset0-500 |
| POST /api/movie/holds |201 new/200 replay; buyer-scoped key;1-10 distinct seats, TTL2-900s |
| GET /api/movie/bookings/{id} |200; group/amount/deadline/current payment; materializes expiry |
| POST /api/movie/bookings/{id}/checkout |202 new/200 replay; booking-scoped key; `{}` allowed |
| GET /api/movie/bookings/{id}/payments |200; retained history ordered by payment number |
| GET /api/movie/payments/{id} |200; state/step/dispatches/amount/refund obligation |
| POST /api/movie/bookings/{id}/cancel |200; releases whole owned group; paid cancellation flags refund |
| GET /api/movie/bookings/{id}/audit |200; first100 ordered actions |
| GET /api/movie/stats |200; shared counts/payment paths/seat-conflict diagnostic |
| POST /api/demo/movie/payments/{id}/reconcile |200 manual one-step dispatch in study mode;404 by default/at gateway |

Invalid JSON/fields400; missing IDs/seats404; inventory/key/hold conflicts409;
database busy/unavailable503. Retry ambiguous writes with their original keys.
Catalog creation is a mutating fixture API without idempotency keys.

## Multiplex and show payloads

```json
{
  "name": "A Multiplex", "zoneId": "Asia/Kolkata",
  "screens": [
    { "name": "Screen 1", "seatCount": 200 },
    { "name": "Screen 2", "seatCount": 250 },
    { "name": "Screen 3", "seatCount": 300 },
    { "name": "Screen 4", "seatCount": 400 },
    { "name": "Screen 5", "seatCount": 500, "categories": { "A": 30, "C": 70 } }
  ]
}
```

Omitted categories default A10/B20/C70. Two or three positive percentages must
add to100. No physical row/column/adjacency model is included. Screen layouts
and show prices are immutable after creation in this version.

Movie payload: `{ "title": "Example", "language": "Hindi", "durationMinutes": 120 }`.

```json
{
  "movieId": "<movie UUID>", "screenId": "<screen UUID>",
  "startsAt": "2026-10-07T10:00:00+05:30", "turnaroundMinutes": 15,
  "pricesMinor": { "A": 50000, "B": 30000, "C": 15000 }
}
```

Use a future timestamp at execution time. Price keys must exactly match screen
categories; values1-1000000 minor units. Scheduling reserves runtime plus cleanup;
a following show may start exactly at `occupiedUntil`.

## Hold and pay

```json
{
  "showId": "<show UUID>", "seatNumbers": [1, 21, 61],
  "buyerId": "alice", "ttlSeconds": 300
}
```

Send `Idempotency-Key: alice-group-1`. Reversed order is the same canonical set.
Changed show/set/TTL under the same buyer/key409. Response gives every selected
category/price, total, currency, state and deadline, bounded by show start.
Booking opens `MOVIE_BOOKING_WINDOW_DAYS` (default 3) local days ahead: a show
dated after today+3 in its multiplex zone returns409 `SHOW_NOT_YET_OPEN`; a show
that already started returns409 `SHOW_STARTED`. See [PVR catalog](PVR_CATALOG_SCHEDULE.md).

Checkout with `Idempotency-Key: alice-payment-1` and `{}`. Optional `delayMs`0-10000
defers normal initial worker eligibility; it is not an HTTP sleep. Response:
`{ "booking": {...}, "payment": {...}, "replayed": false }`. Poll GET booking.
After FAILED, a new key admits another payment while still HELD. An old key
finds its original attempt and current booking state, even after newer payments.
No new payment extends expiry; concurrent unresolved attempts409.

Booking states HELD/PAYMENT_PENDING/CONFIRMED/EXPIRED/CANCELLED; payment states
PENDING/RETRY_PENDING/SUCCEEDED/FAILED. Success can carry `refundRequired=true`
when confirmation is impossible or a paid group is cancelled.

In `compose.movie.yml` only, checkout accepts `testBucket`:0 first success,9500
retry success,9950 final failure. Manual reconcile can skip eligibility/backoff
to advance the fixture. Default fixture input400; manual reconcile404. Request
keys bind delay/testBucket. These are local controls, not payment-provider APIs.

Import [collection](../postman/movie-booking.postman_collection.json) and
[environment](../postman/movie-booking.postman_environment.json), in manual mode.
