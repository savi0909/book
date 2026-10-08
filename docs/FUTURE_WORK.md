# Future work and next-session handoff

Updated 2026-10-07. Start with [current project status](PROJECT_STATUS.md).
The **Java virtual-thread load tester is delivered**, with its own Docker group,
14 passing tests,8 Postman requests/9 assertions and a locally verified 100-call
10 RPS smoke. Read [Java tutorial](JAVA_LOAD_TESTER_TUTORIAL.md) and
[verification](../load-tester-java/docs/VERIFICATION.md). The earlier Node controlled
comparison remains retained; do not rebuild either from an older planning note.

> **Current state (2026-10-08):** origin is `https://github.com/savi0909/book`
> (work merges through PRs). PostgreSQL runs at 3 GB / 2 CPUs (tuned); APIs at
> 512 MB / 1 CPU. The Java loader defaults to 1 CPU, but the retained container
> still has 0.5 CPU. The loader has 19 tests and includes movie journeys
> (`POST /load/movie-runs`). Statements below that say "origin absent",
> "PostgreSQL 1 GiB", "14 Java tests", "0.5 CPU" or "movie load unbuilt" are dated
> history. Start with [project status](PROJECT_STATUS.md#study-path) and its study path.

At 12:21 IST, APIs/gateway/PostgreSQL and Java/Node generator containers were
observed stopped and retained. PostgreSQL keeps its 1 GiB limit and existing volume.
No application tests or workload were run during this documentation refresh.

Next pending work is the selected **Ankita checkout/private connectivity/finite
run**. Java automatic restart discovery, paired live-fault orchestration, shared
hold admission and movie group/payment load remain separate extensions. Redis
availability is deferred to **phase 3**, with a proposed 500 MiB allocation and
PostgreSQL remaining seat-ownership authority. No Redis/cache/SSE exists here yet.

## Earlier deliveries — 2026-10-06

Latest 2026-10-06: [local separate Docker 10 RPS smoke](DOCKER_LOAD_TEST_TUTORIAL.md)
passed 100/100 fresh holds after PostgreSQL was raised to 1 GiB. User deferred
Redis to **phase 3**, for high-throughput/quick availability updates, with a future
500 MiB budget. No Redis service/cache/push added; database ownership remains
authoritative. Phase 3 requires separate implementation selection; shared hold
admission and movie publishing/streaming remain unbuilt.

Latest 2026-10-06: [checkoutable generic client module](HOLD_LOAD_TEST_TUTORIAL.md)
implemented/[tested locally](HOLD_LOAD_TEST_VERIFICATION.md); final business run
belongs on Ankita and remains pending. Scoped ingress/observer delivered but not
deployed on the tailnet. User-authorized bounded loopback checks passed; their
ingresses closed and fixtures remain. Shared admission/movie load/live feed unbuilt. Earlier
teaching/planning notes below describe their historical session boundaries.

Resume with [project context](../PROJECT_CONTEXT.md) and [worklog](WORKLOG.md);
read [agent instructions](../AGENTS.md) before changing this repository.

For a fresh implementation session, use the
[hold-retry/admission handover prompt](HANDOVER_HOLD_RETRY_BUILD.md).
Creating the handover does not execute its instructions in the current session.

Current movie domain:5-100 screens/multiplex,200-500 seats/screen, two or three
categories (default 10/20/70), independent shows, atomic 1-10-seat groups and
95/4.5/0.5 mock payment paths. Failed payment retains the original deadline/group;
a fresh key creates a fresh attempt. Read [tutorial](MOVIE_BOOKING_TUTORIAL.md),
[API](MOVIE_API_REFERENCE.md), [ADR](ADR_001_MOVIE_GROUP_BOOKING.md) and
[verification](MOVIE_VERIFICATION.md).

The selected later work is [thousands-user same-day simulation](MOVIE_LOAD_SIMULATION_PLAN.md).
The earlier **do not implement yet** override applied to the planning session;
later selections delivered the generic load clients. Read the
[hot-show availability and retry-storm plan](HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md).
Existing generic scenario 2 protections and generic client comparison are available
for study. Availability streaming/shared admission still need separate selection.
Existing logical expiry is implemented; live client updates are not.
Future roles are selected: Abhishek hosts services; Ankita generates load over
Tailscale. Java tool/budgets are documented; private remote ingress and actual
Ankita execution remain pending. No sustained capacity run is recorded.

Other proposals remain separate:

- Frontend, browse/search filters, visual/adjacent seat maps and groups over 10.
- Movie transition outbox/notifications; inherited generic outbox does not publish movie changes.
- Independent mock process, bounded movie admission and poison policy; current mock shares DB availability.
- Actual refund workflow, buyer authentication, callback verification and retention/idempotency policy.
- Catalog/layout/schedule edits with a defined policy for existing bookings.
- Show partitioning, waiting rooms, read caching and DB HA driven by measured needs.
- Original generic scenarios 5-6, optimistic seats and a larger retry-storm study
  beyond the delivered finite generic clients remain proposals.

Repository is `D:/sd-book-my-show`, main branch, Maven artifact `sd-book-my-show`.
Earlier 14 source snapshots use disclosed September 15-October5 reconstructed
dates. Setup/movie work uses actual timestamps. Do not rewrite real evidence dates.
Publishing awaits a fresh empty GitHub repository URL. Do not reuse the original
Java workspace origin. Push without force and verify the remote SHA matches HEAD.

Addresses: A 8130/B 8131/gateway 8132/PostgreSQL 5553; optional generic provider 8133.
Movie needs no provider container. Preserve original repositories/IntelliJ edits/
services and all data. No file/directory deletion or Maven clean/prune/reset without
explicit user permission. Check verification for final scheduled/manual stack state.

Historical movie handoff on 2026-10-05: the standalone base+failover stack was running, both
legacy/movie workers enabled, movie controls off. 68 tests,23 A/B runtime checks,
30 movie requests/60 assertions and 75 generic requests/110 assertions passed.
Pending movie payments and confirmed ownership mismatches were both 0 at that check. Resume
with the tutorial; implement sustained simulation only when the user selects it.
