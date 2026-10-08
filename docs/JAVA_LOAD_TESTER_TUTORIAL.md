# Spring Boot virtual-thread load tester for Book My Show

Selected 2026-10-06: user requested the same Java/Spring Boot approach as the
earlier URL-shortener generator. Implemented in [load-tester-java](../load-tester-java/README.md),
a standalone app inside this repository, with its own Docker group. Earlier
[Node comparison](HOLD_LOAD_TEST_TUTORIAL.md) and fixtures remain retained.
This Java module targets the existing generic ticket endpoints in Book My Show;
movie V5 atomic group holds/payments/live availability remain separate work.

> **Current state (2026-10-08):** on 2026-10-07 the same service gained a second
> runner, `POST /load/movie-runs`, which drives movie advance-booking journeys
> (browse → seat map → group hold → checkout → poll → optional cancel). This
> tutorial still covers the generic runner it was written for. The movie runner
> is taught in [movie advance-booking tutorial, part 6](MOVIE_ADVANCE_BOOKING_TUTORIAL.md#part-6--the-movie-journey-workload).
> The loader now has 19 tests (14 here + 5 in `MovieLoadTest`).

## What came from the reference

The actual reference is `D:/java-projects/url-shortener-load-test`, rather than
the shortener backend itself. Its LoadRunService dispatches fixed-time arrivals
to `Executors.newVirtualThreadPerTaskExecutor()`. ClientConfig builds a blocking
Spring RestClient with JDK HTTP transport; LoadController starts and reports runs.
We retain that programming model and adapt the booking contract. See
[mapping and deliberate differences](../load-tester-java/PARITY.md).

Virtual threads cheaply park while waiting for HTTP. They do not increase database
connections or make PostgreSQL seat contention disappear. This tester also bounds
logical concurrency: if all slots are occupied at the next scheduled arrival, it
stops instead of growing a queue or silently lowering its arrival rate. Backoff
retains the logical slot, but never a booking database connection.

One configured gateway already balances the booking A/B services, so a second
Spring Cloud client balancer adds no value here. Management 8135 is separate from
backend 8130/8131 and gateway 8132. Load Compose is separate from the business stack.

## Contracts and pacing

Every start creates one fresh generic event (setup traffic, not measured), then
dispatches exactly `rate * seconds` scheduled operations unless stopped. HOLD
reserves cyclic seat numbers, AVAILABILITY reads the first 100 seats, and MIXED
uses seeded hold/read choices. 100 seats and 100 holds normally yield 100 fresh 201s.
Fewer seats deliberately yield 409 conflicts. Keys/buyers contain a fresh 16hex run
ID, so the second comparison arm cannot accidentally replay the first arm.

Defaults are 10 initial operations/s for 10s,100 seats, eight logical slots, one
attempt,3.5s request timeout and 10s arrival-relative deadline. The final arrival
is due at 9.9s: elapsed completion near 9.9s is consistent with the nominal 10s
arrival window. Retries increase HTTP calls; the report records those separately
from offered initial operations. Setup includes a readiness read and one event POST.

NONE means one attempt. Explicit IMMEDIATE/JITTER allow at most 4 attempts total.
Both honor Retry-After floors (seconds or HTTP date); JITTER adds seeded full-jitter
windows 500/1000/2000ms. Only 0/408/429/502/503/504 are retry candidates. 400 and 409
are terminal. A transport timeout or 5xx can hide a committed hold: exhaustion,
or a later 409 after ambiguity, stays UNKNOWN. The original buyer/key/payload is
retained. Successful 200/201 must match the exact booking identity and replay flag.

Whole-exchange timeout covers headers and the body. If cancellation fails to close
the prior HTTP task promptly, retries stop and no new run/discovery is admitted
while that task remains open. One application attempt is one RestClient invocation;
the tester does not claim to prove server receipt or server-side cancellation.

Unknown holds can have one separate discovery phase: at most 2 calls per unknown,
one 10s phase deadline, same identity, no refreshed TTL. Its calls and timing do not
inflate the primary arm's latency/attempt count. Java discovery currently uses
completed in-process history; after a crash, retain the forced journal/manifest
and do not invent a fresh key or reset a marker. Automatic Java restart recovery
and Node manifest compatibility are not implemented.

## Local Docker startup — explicitly selected exception

Run from the repository root. Services must already be in their normal
`compose.yml + compose.failover.yml` group. Do not start other labs.

```powershell
Set-Location D:/sd-book-my-show
mvn -B -ntp -f load-tester-java/pom.xml verify
docker build -t sd-book-my-show-java-load-test:local load-tester-java

# Choose a new suffix every time; retain previous STOP files/results.
$javaSuffix = Get-Date -Format yyyyMMdd-HHmmss
$javaObserver = "load-test/results/java-observer-$javaSuffix"
$javaControl = "load-test/results/java-control-$javaSuffix/STOP"
node load-test/observe.mjs $javaObserver 60 $javaControl
```

The observer occupies that terminal until finished. In a second terminal, after
its `observerReady` message, use the same suffix:

```powershell
Set-Location D:/sd-book-my-show
$javaSuffix = 'COPY_THE_SUFFIX_FROM_TERMINAL_ONE'
$env:JAVA_LOADER_OBSERVER_STOP_FILE = "/results/java-control-$javaSuffix/STOP"
docker compose -f load-tester-java/compose.local.yml up -d
Invoke-RestMethod http://127.0.0.1:8135/actuator/health

$javaRun = Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8135/load/runs -ContentType application/json `
  -Body '{"mode":"HOLD","rate":10,"seconds":10,"seats":100,"policy":"NONE","maxAttempts":1}'
$javaStatusUrl = "http://127.0.0.1:8135/load/runs/$($javaRun.runId)"
do {
  Start-Sleep -Milliseconds 500
  $javaResult = Invoke-RestMethod $javaStatusUrl
} while ($javaResult.state -in @('PREPARING','RUNNING','DISCOVERING'))
$javaResult | ConvertTo-Json -Depth 15

# Retained service/data/results; stops only this generator group.
docker compose -f load-tester-java/compose.local.yml stop
```

Local bounds: rate<=10,seconds<=10,total<=100,concurrency<=8. The tester requires
the observer's fresh heartbeat (<15s); STOP, missing heartbeat, heap 85%, twenty
consecutive transient failures,100ms schedule lag or concurrency saturation stops
new work. Admitted calls drain within their attempt/deadline bounds. Observer
checks host/service CPU/memory and PostgreSQL connection/lock-wait pressure; actual
sampling is around 2s because Docker stats takes time. It collects no business
HTTP traffic or SQL query text. Remote file synchronization is a separate concern.

Docker project `sd-book-my-show-java-load-test` joins only the existing booking
network. It publishes management on 127.0.0.1:8135, allocates 384 MiB/0.5 CPU,
uses 192 MiB Java heap and retains metadata under `load-test/results/java/<runId>`.
The older Node group stays retained. PostgreSQL stays 1 GiB, with its same volume.
Redis is not added: phase 3 proposes 500 MiB for advisory availability updates.

> **Current state (2026-10-08):** the paragraph above is the 2026-10-06 allocation.
> `compose.local.yml` now gives the loader **1 CPU**, and the Dockerfile sizes the
> heap as 50% of 384 MiB (≈192 MiB) without a fixed `-Xmx`. The retained container
> still has 0.5 CPU until it is recreated. PostgreSQL is now **3 GB / 2 CPUs**. See
> [project status](PROJECT_STATUS.md) and [sizing, part 7](MOVIE_ADVANCE_BOOKING_TUTORIAL.md#part-7--sizing-containers).

## Ankita checkout and run

Clone `https://github.com/savi0909/book` (origin exists since 2026-10-07; the earlier
Git-bundle transfer predates it). Build the standalone loader on Ankita with Java 21/Maven and Docker.
Unlike local Compose, the [Ankita file](../load-tester-java/compose.ankita.yml)
does not require Abhishek's Docker network.

```powershell
Set-Location D:/sd-book-my-show
mvn -B -ntp -f load-tester-java/pom.xml verify
$env:BOOKING_GATEWAY = 'http://100.103.238.2:8132'
# This address is an example, NOT verified remotely; check private forwarding first.
Invoke-RestMethod "$env:BOOKING_GATEWAY/actuator/health/readiness"
docker compose -f load-tester-java/compose.ankita.yml up -d --build
# Start/poll through Ankita's own localhost:8135, using the same JSON as above.
```

Abhishek's gateway currently binds loopback. No Tailscale configuration was
changed in this delivery. The example address needs an explicitly scoped private
gateway path before it works; do not assume loopback ports are reachable over
Tailscale. The old Node scoped ingress accepts only its own exact hold manifest,
and is not a replacement for this Java service's readiness/fixture/read routes.
Remote mode checks a literal 100.64.0.0/10 target and hostname containing Ankita
(the remote container declares ankita-loader). This is a lab configuration guard,
not authenticated host identity. Ankita HTTP execution is pending.

Remote bounds are 200 initial operations,32 logical slots,60s arrival window and
75s total wall guard. These deliberately remain small learning runs. Abhishek
must observe services; no shared heartbeat path is configured between laptops.
Until such a path exists, monitor Abhishek's metadata and manually call the stop
API on Ankita if server limits trip. Do not label these as automatic distributed
admission or a sustained high-throughput benchmark.

## REST walkthrough, source reading and exercises

Import the [collection](../load-tester-java/postman/java-load-tester.postman_collection.json)
and [environment](../load-tester-java/postman/local.postman_environment.json),
then run in order with 1200ms delay and a live observer. It sends one measured
hold, checks completed report/list/empty discovery/duplicate discovery/stop/bounds.
Full route/field definitions are in [API reference](../load-tester-java/docs/API_REFERENCE.md).

Read this order in IntelliJ:

1. [RunSpec](../load-tester-java/src/main/java/com/example/bookingload/RunSpec.java): normalize defaults; compare local/remote limits.
2. [LoadController](../load-tester-java/src/main/java/com/example/bookingload/LoadController.java): follow 202 admission and 409 exclusivity.
3. [LoadRunService](../load-tester-java/src/main/java/com/example/bookingload/LoadRunService.java): execute → fixed arrivals → invoke → report.
4. [BookingClient](../load-tester-java/src/main/java/com/example/bookingload/BookingClient.java): virtual HTTP task, RestClient, body cap, timeout and closure guard.
5. [LoadTesterTest](../load-tester-java/src/test/java/com/example/bookingload/LoadTesterTest.java): lost commit, replay, discovery, saturation and heartbeat expiry.
6. Movie runner (added 2026-10-07): [MovieSpec](../load-tester-java/src/main/java/com/example/bookingload/MovieSpec.java) →
   [MovieRunService](../load-tester-java/src/main/java/com/example/bookingload/MovieRunService.java) →
   [MovieChoices](../load-tester-java/src/main/java/com/example/bookingload/MovieChoices.java) →
   [RunSlot](../load-tester-java/src/main/java/com/example/bookingload/RunSlot.java). It is walked through in the advance-booking tutorial.

Exercise: predict the outcomes for 10 HOLD operations with 2 seats and no retries.
Then use a separately named, observer-backed small run (`rate:10,seconds:1,seats:2`).
Expect 2 new holds and 8 business conflicts; conflicts are not capacity errors.
For retry-policy comparison, start IMMEDIATE and JITTER as separate fresh runs
with the same seed/arrival/budget. Without transient faults, both should usually
have one call per hold; that does not demonstrate jitter's overload benefit.
Use the HTTP tests to study loss/busy cases; Java fault injection into the live
backend and automatic paired orchestration are not implemented.

Interview points: virtual threads change waiting cost, not downstream capacity;
fixed arrivals prevent slow responses from silently lowering offered load;
idempotency enables commit discovery, not fewer attempts; jitter needs deadlines,
attempt limits and Retry-After; local smoke results do not prove remote capacity.

## Actual evidence

The first Java Docker smoke returned 100/100 HTTP 201 at 10 calls in each of ten buckets;
HTTP avg 9.47ms/p50 8.82ms/p95 14.10ms/p99 18.37ms, no errors/unknowns. All six SQL
violations were 0. The final package also returned 100/100 HTTP 201 at 10/s, with HTTP
avg 14.11ms/p50 8.65ms/p95 35.56ms/p99 119.47ms and all SQL violations 0. Its higher
tail is recorded rather than treated as a performance comparison. Postman passed
8 requests/9 assertions and retained one further event/hold. Both generator
containers were stopped/retained; SIGTERM 143 followed graceful shutdown. Complete evidence is recorded in
[verification](../load-tester-java/docs/VERIFICATION.md) and
[metadata JSON](evidence/JAVA_LOAD_TESTER_2026-10-06.json). This is a bounded local
functionality check, not a Java-versus-Node benchmark or an Ankita result.
