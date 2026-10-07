# Book My Show project status

Updated **2026-10-07** (13:30 IST). Repository: `D:/sd-book-my-show`, branch
`feature/pvr-rolling-catalog`. Latest delivery: **permanent PVR catalog and rolling
today+3 show window** ([details](PVR_CATALOG_SCHEDULE.md)); previous implementation
commit 8f9fd03 (Java load tester). Read [project context](../PROJECT_CONTEXT.md)
for domain rules and [worklog](WORKLOG.md) for dated delivery history.

## Delivered

| Area | Status | Read next |
| --- | --- | --- |
| Generic ticket booking | Implemented: seat locks, durable hold/checkout keys, database-time expiry, payment recovery and late-success refund obligations | [API](API_REFERENCE.md), [verification](VERIFICATION.md) |
| Generic failure scenarios 1–4 | Delivered: API failover, provider isolation, poison-job handling, transactional outbox | [scenario guide](FAILURE_SCENARIOS.md) |
| Movie V5 | Implemented: multiplex/screens/categories/shows, atomic 1–10-seat groups, immutable hold deadlines and mock payment histories/retries | [tutorial](MOVIE_BOOKING_TUTORIAL.md), [verification](MOVIE_VERIFICATION.md) |
| PVR catalog + rolling window | Implemented: V6/V7 permanent 303 PVR/INOX sites, 1,971 screens; maintainer fills today+3 and purges past days; holds beyond window 409 | [PVR catalog](PVR_CATALOG_SCHEDULE.md) |
| Java load tester | Delivered: Java 21 / Spring Boot 3.5.16, virtual-thread RestClient, fixed arrivals, HOLD/AVAILABILITY/MIXED, finite retries and unknown-hold discovery | [module](../load-tester-java/README.md), [tutorial](JAVA_LOAD_TESTER_TUTORIAL.md) |
| Node comparison module | Retained: controlled generic hold retry comparison, scoped default-off faults, journals, observer and capped discovery | [runbook](HOLD_LOAD_TEST_TUTORIAL.md) |
| Resources and Docker separation | PostgreSQL 1 GiB; Java generator owns `sd-book-my-show-java-load-test` with 384 MiB / 0.5 CPU; Node group remains separate | [setup](STANDALONE_SETUP.md), [Java Compose](../load-tester-java/compose.local.yml) |

The Java generator is the user's latest selected implementation. Its workload
currently uses **generic ticket endpoints in this Book My Show repository**.
Movie group holds/payments and live availability traffic are not covered by it.
Client concurrency caps are not shared A/B hold admission. Generic provider and
outbox protections do not automatically apply to the movie domain.

## Verified results — executed on 2026-10-07 (PVR catalog)

| Check | Result |
| --- | --- |
| `mvn -B -ntp verify` | 74 tests (69 earlier + 5 `MovieScheduleIntegrationTest`); zero failures/errors/skips |
| Live Flyway | V6 + V7 applied to retained `booking-data` in 1.24 s (API B; API A waited on Flyway lock) |
| First maintainer tick (API B) | 40,333 shows / 14,254,490 seat rows in 467.8 s; purged 1 past fixture show, 2 bookings, 3 payments, 1 refund-required |
| Window per local day | 7 Oct 7,236 (future slots only, plus 2 retained fixture shows) · 8 Oct 11,026 · 9 Oct 11,046 · 10 Oct 11,027 |
| Live SQL audit | Seat-count mismatches 0, overlapping shows 0, past shows 0, catalog screens missing day 3 0 |
| Gateway reads | Multiplex/shows/seats via 8132 returned tier-1 prices and AVAILABLE seats |
| Database size | 1,723 MB after the fill (was 9.5 MB) |

No bookings, holds or load were generated against the catalog.

## Verified results — executed on 2026-10-06

| Check | Result |
| --- | --- |
| Independent Java loader Maven verification | 14 tests; zero failures/errors/skips |
| Java loader Postman/Newman | 8 requests, 9 assertions; zero failures |
| Java local 10 RPS smoke | 100 fresh holds, 100 HTTP 201 responses, ten one-second buckets of 10 calls |
| Final smoke SQL audit | All six violation aggregates zero; 100 HELD at audit |
| Final smoke HTTP timing | Average 14.11 ms, p50 8.65 ms, p95 35.56 ms, p99 119.47 ms |
| Retained Java delivery fixtures | Three events / 201 bookings across two smokes and Postman |

Evidence: [Java verification](../load-tester-java/docs/VERIFICATION.md) and
[recorded metadata](evidence/JAVA_LOAD_TESTER_2026-10-06.json). The last small
finalization fix was followed by another successful 14-test run and image build.
These are bounded local functionality results, not an Ankita measurement or a
capacity benchmark. Historical backend 69-test and Node 28-test results remain
their own earlier evidence. No application tests or load runs were repeated for
this documentation refresh.

## Current runtime — 2026-10-07 at 13:30 IST

Base + failover Compose is **running**: API A/B, gateway 8132 and PostgreSQL
healthy, with `MOVIE_SCHEDULE_ENABLED=true` on both APIs. Starting the stack
**recreated** the PostgreSQL container from Compose; the named `booking-data`
volume and the 1 GiB / 2 GiB memory+swap limits were retained. The Java and Node
load-generator groups remain stopped. The section below is the earlier 12:21 snapshot.

## Earlier runtime — observed 2026-10-07 at 12:21 IST

| Component | Observed state |
| --- | --- |
| Booking API A / B | Stopped, containers retained |
| HAProxy gateway | Stopped, container retained |
| PostgreSQL | Stopped; same container, 1,073,741,824-byte limit and retained `sd-book-my-show_booking-data` volume |
| Java generator | Both service and one-off containers stopped and retained |
| Node generator | Service and test containers stopped and retained |
| Redis | Not deployed in this project; deferred to phase 3 |

[Status observation](evidence/PROJECT_STATUS_2026-10-07.json) records container
state and resource settings. The database was not queried while stopped; retained
volume presence does not constitute a new database-integrity audit. The October 6
healthy-service observations remain historical. This refresh made no runtime
changes; use [setup](STANDALONE_SETUP.md) when a later task calls for startup.

## Pending and deferred

1. **Ankita execution:** checkout/build the Java module, verify the private gateway
   path, then run the selected finite workload. Abhishek hosts services; Ankita
   generates final business load. Remote forwarding/reachability and performance
   remain pending; this refresh did not inspect Ankita or configure Tailscale.
2. **Java recovery and experiment extensions:** automatic crash/restart discovery,
   Node manifest compatibility, live fault injection and automatic paired arms
   are not implemented in the Java service. The Node study remains available.
3. **Shared hold admission:** unbuilt; select and measure it separately. Existing
   caller/ingress caps do not establish a shared server boundary.
4. **Movie/high-throughput work:** group/payment simulation, movie transition
   publishing/SSE, waiting rooms and 100K/hot-show experiments remain later work.
5. **Phase 3 Redis:** deferred advisory availability updates, proposed 500 MiB
   budget. No Redis cache or push feed exists yet; PostgreSQL stays authoritative
   for seat ownership. Redis implementation needs a separate selection.

See [future work](FUTURE_WORK.md) and [resume handover](NEXT_SESSION_HANDOVER.md).
Mentioning pending work does not authorize its execution.

## Publishing

No `origin` is configured, so no push has occurred. Commit task changes locally;
create/verify a complete-history Git bundle for transfer while origin is absent.
The implementation bundle for `8f9fd03` remains retained under `target/`.
Once the user supplies this project's origin, push without force and verify the
remote SHA. Never reuse the original Java workspace's remote.
