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
| Resources and Docker separation | Defaults applied 2026-10-07: PostgreSQL 3 GB / 2 CPU (shared_buffers 768MB, effective_cache_size 2GB, work_mem 16MB, maintenance_work_mem 256MB, shm 256MB); APIs 512 MB / 1 CPU each (heap 60% ≈ 308 MB, Serial GC); gateway 96 MB / 0.5 CPU; Java loader 384 MB / 1 CPU (heap 50%); Node group separate | [setup](STANDALONE_SETUP.md), [Java Compose](../load-tester-java/compose.local.yml) |

The Java generator is the user's latest selected implementation. Its workload
currently uses **generic ticket endpoints in this Book My Show repository**.
Movie group holds/payments and live availability traffic are not covered by it.
Client concurrency caps are not shared A/B hold admission. Generic provider and
outbox protections do not automatically apply to the movie domain.

## Movie advance-booking simulation — executed on 2026-10-07

| Run | Result |
| --- | --- |
| Smoke 1 (5/s × 20 s) | Exposed a loader bug: it expected 201 from checkout, but the API returns 202. All 77 server-side checkouts confirmed; the loader stopped following them |
| Smoke 2 after fix | 100 journeys: 61 CONFIRMED, 16 CANCELLED, 12 ABANDONED, 11 BROWSED; every call 2xx |
| Main (20/s × 300 s) | **User-stopped** at 537 journeys: 339 CONFIRMED, 60 CANCELLED, 79 ABANDONED, 53 BROWSED, 6 stopped mid-poll; 2,459 calls, all 2xx; hold p50 9.0 ms / p95 32.6 ms |
| Database after runs | Movie bookings from journeys: 482 CONFIRMED, 76 CANCELLED, 104 EXPIRED; 0 open payments |

**Correction (2026-10-07):** the JIT fix (46ff584) was first verified against an
API image built from a stale jar. `mvn test` does not repackage, so the low CPU
reading was not evidence of the fix. The fixed jar was packaged with `-DskipTests`
and deployed together with the resource defaults. The deployed class was checked
to contain `jit = off`, and PostgreSQL sat at 3.5% CPU after the startup tick.

Also found and fixed during setup:
- **Maintainer JIT cost.** Each no-op batch took 733 ms with JIT and 8.4 ms
  without, so every tick pinned a PostgreSQL core. Fix: `SET LOCAL jit = off`
  (commit 46ff584).
- **Git Bash path mangling.** Git Bash rewrote `/results/...` in the loader's env
  var into a Windows path, so run Compose with `MSYS_NO_PATHCONV=1`.

Host memory was at about 91%. To make room, three unrelated stacks were
**stopped** (data kept) and the Docker VM page cache was dropped.

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

PostgreSQL limit is now **3 GB / 2 CPUs** (live `docker update`, persisted in `compose.yml`). Base + failover Compose is **running**: API A/B, gateway 8132 and PostgreSQL
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
