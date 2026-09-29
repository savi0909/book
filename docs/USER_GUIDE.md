# User guide

## Optional poison-job study

Start [scenario3 tutorial](POISON_JOB_TUTORIAL.md) for exact base+failover+poison
commands, source reading and IntelliJ checkpoints. It uses the default simulator,
retained PostgreSQL5547 and APIs8105/8106/gateway8107. Both automatic maintenance
loops are disabled in the poison overlay: use explicit recovery ticks. Run
scripts/learn-poison.mjs and then the poison Postman collection sequentially;
both create retained fixtures. Import [collection](../postman/poison.postman_collection.json)
and [environment](../postman/poison.postman_environment.json). Expect a healthy
booking to confirm while a bad item reaches UNKNOWN quarantine after3 failures.
Fix the fixture and redrive using a unique key; same-key replay must not dispatch.
The runtime harness briefly restarts A and pauses/unpauses this project's DB.
Use base+failover without poison to resume scheduled maintenance after study.
Quarantine persists across topology changes; it is never payment failure.

Historical provider host8123 is currently owned by URL-shortener C; do not start
the provider overlay on that port. Other project services remain undisturbed.

## Optional API failover study

Start [the detailed scenario 1 tutorial](API_FAILOVER_TUTORIAL.md) for the optional
`compose.failover.yml` overlay, shared endpoint `http://localhost:8107`, local
drain/resume controls, Postman collection and real crash/graceful-stop experiment.
The original API ports and database volume are retained. Run its failure script
separately from either Postman collection or the original runtime script.

## Start and import

Use Java 21, Maven 3.6.3+ (verified here with 3.9.11), Node for the optional runtime
script and a working Docker engine. The lab needs ~900 MB of configured container
memory. Confirm ports 8105, 8106 and 5547 are free; preserve other learning stacks.

```powershell
Set-Location D:/java-projects/ticket-booking-lab
mvn -B -ntp verify
docker compose config --quiet
docker compose build api-a api-b
docker compose up -d --wait
docker compose ps
Invoke-RestMethod http://localhost:8105/health
Invoke-RestMethod http://localhost:8106/health
```

Expect api-a and api-b instance values. Overall `/actuator/health` includes the
database. Readiness includes `readinessState,db` and withdraws during drain or DB
failure. Liveness remains separate from dependency health and admission.

In Postman import [the collection](../postman/ticket-booking-lab.postman_collection.json)
and [environment](../postman/local.postman_environment.json), select **Ticket booking
local**, then run the complete collection in order. The collection creates a unique
event and scopes keys/identities by a new run UUID. Chained IDs live in the selected
local environment; rerun the initial fixture request to start a new exercise.
Polls pause 250ms and stop after 60 tries. Writes, outcomes and audits remain in
the database. There is no reset endpoint and no browser dashboard in this API lab.

CLI equivalent:

```powershell
npx --yes newman@6.2.2 run postman/ticket-booking-lab.postman_collection.json -e postman/local.postman_environment.json --reporters cli,json --reporter-json-export target/postman-evidence.json
```

Do not run the runtime failure script while running Postman: it temporarily stops
APIs and pauses/restarts the database. The npm command downloads/runs the pinned
Newman package when it is not cached; no repository-wide npm install is required.

## Walk one reservation manually

```powershell
$bookingBase = 'http://localhost:8105'
$bookingRun = [guid]::NewGuid().ToString()
$bookingEvent = Invoke-RestMethod -Method Post -Uri "$bookingBase/api/demo/events" -ContentType 'application/json' -Body (@{name="manual-$bookingRun";seatCount=8} | ConvertTo-Json)
$bookingHoldBody = @{eventId=$bookingEvent.id;seatNumber=1;buyerId="alice-$bookingRun";ttlSeconds=120} | ConvertTo-Json
$bookingHeaders = @{'Idempotency-Key'="hold-$bookingRun"}
$bookingHold = Invoke-RestMethod -Method Post -Uri "$bookingBase/api/holds" -Headers $bookingHeaders -ContentType 'application/json' -Body $bookingHoldBody
$bookingHold
# Retry exactly the same request through the other replica.
Invoke-RestMethod -Method Post -Uri 'http://localhost:8106/api/holds' -Headers $bookingHeaders -ContentType 'application/json' -Body $bookingHoldBody
$bookingCheckout = Invoke-RestMethod -Method Post -Uri "$bookingBase/api/bookings/$($bookingHold.id)/checkout" -Headers @{'Idempotency-Key'="checkout-$bookingRun"} -ContentType 'application/json' -Body '{"scenario":"SUCCESS","delayMs":0}'
Invoke-RestMethod "$bookingBase/api/bookings/$($bookingHold.id)"
```

The first hold is HELD; retry has the same ID and replayed true. Checkout returns
202/CHECKOUT/PENDING. Within a normal maintenance tick it becomes CONFIRMED/SUCCESS.
GET again if it is still pending. No money was charged. POST checkout with the same
key/input returns 200 and the existing payment, regardless of later booking state.
Changing scenario/delay/key conflicts; this bounded model allows one attempt per
booking. Holding seat 1 with another buyer/key now returns 409.

Inspect `/api/events/{eventId}/seats` and `/api/bookings/{bookingId}/audit` to connect
availability and state changes. Cancellation is POST `/api/bookings/{bookingId}/cancel`.
Confirmed cancellation releases the seat and flags REFUND_REQUIRED; POST
`/api/demo/payments/{paymentId}/refund` acknowledges a simulated refund. It records
REFUNDED_SIMULATED and performs no external action.

## Small failure exercises

| Experiment | How | Expected evidence |
| --- | --- | --- |
| Contested seat | Fresh event; many buyer/key pairs request seat 1 through A/B | Exactly one fresh hold; others 409. Unique active-seat count remains one |
| Unknown outcome | Checkout scenario UNKNOWN, delayMs 0; observe quickly or use manual reconcile | Payment UNKNOWN while receipt says SUCCESS internally, then recovery confirms before expiry |
| Late success | Hold TTL 2s, checkout SUCCESS delayMs 4000; after expiry hold same seat for a new buyer | Old booking EXPIRED/payment SUCCESS/refund required; new buyer keeps HELD |
| Cancel pending checkout | Checkout UNKNOWN with 2000ms delay, cancel before outcome | Cancellation persists; recovered success flags refund instead of confirming |
| Worker crash gap | Runtime script stops both APIs after unknown acceptance, restarts PostgreSQL/APIs | Same payment ID recovers, hold key still returns original ID |
| DB unavailable | Runtime script briefly pauses only this project's PostgreSQL | Seat/event operations 503; /health still 200; operations resume after unpause |

Run the bounded automated version:

```powershell
node scripts/learn-booking.mjs
```

It uses fresh fixture IDs, races 24 distinct buyers/20 duplicate holds/12 duplicate
checkouts/12 callbacks, then exercises late success, cancellation, failure, restarts
and outage. Its finally block restores this stack and waits for database health.
Assertions and polls appear in `target/runtime-evidence.json`; counts can vary with
poll timing. This is a correctness exercise, not a throughput benchmark.

Prediction prompts: why does a same-key retry return EXPIRED instead of another
hold? Why must expiry be checked after waiting for the seat lock? What happens if
success arrives after the seat has been reassigned? The explanation is in
[the spec](SYSTEM_SPEC.md) and [interview guide](INTERVIEW_GUIDE.md); answering is
optional and no mastery claim is recorded automatically.

## Troubleshooting and stopping

If Docker is unavailable, Maven integration tests fail instead of silently skipping
real database checks. If ports conflict, edit this project's Compose mappings and
Postman environment together; keep container JDBC DNS/port separate from host ports.
After code changes run Maven verify and rebuild this project's API images before up.
Migration failure: inspect `docker compose logs postgres api-a api-b`; preserve data
and resolve the schema issue without deleting the volume. Flyway owns schema creation.

409 is an expected business result. 503 can mean database loss or a busy lock; retry
the same logical hold/checkout key with bounded backoff. An HTTP timeout can follow
a commit. Generate a new key only for a new logical request, not to evade a replay.
Provider callbacks need an accepted receipt and its available time; early/mismatched
callbacks reject. Reconcile returns current state and may leave an active lease or
not-yet-ready outcome unresolved; GET/poll later.

```powershell
docker compose stop
# Later, reuse the retained event/booking data.
docker compose up -d --wait
```

No clean, reset, volume removal or global Docker cleanup is required. Previous
projects and original locking/payment stacks are preserved. No original scripts
are needed for this new lab.

## Provider outage study (scenario 2)

Read [the full tutorial](PROVIDER_ISOLATION_TUTORIAL.md) and use the three-file
Compose commands in [README](../README.md). Run node scripts/learn-provider.mjs
from this directory; it creates retained fixtures and restores NORMAL mode.
Import [provider collection](../postman/provider.postman_collection.json) with
[environment](../postman/provider.postman_environment.json); Newman uses
--delay-request500 (with a space: `--delay-request 500`). Fault controls are shared;
run one experiment/collection at a time. Inspect GET /api/provider/status and
GET /api/payments/{id}/recovery. Exhausted UNKNOWN needs operator reconcile using
the same payment ID. Repeated manual calls are not an automatic retry strategy.

On interruption restore with POST http://localhost:8123/control?NORMAL. Both APIs
remain useful during provider failure; new checkout may503/PAYMENT_BACKLOG_FULL
when shared count/age budget is exceeded. Replay the original key/input and
inspect current state rather than creating another intent. Success after expiry
can require refund reconciliation. The original booking collection assumes the
default simulator's unlimited polling and must run without PROVIDER_URL. Retain
and resolve uncertain remote intents before switching simulator authority.
Stop/resume with all selected Compose files; do not delete either data volume.
