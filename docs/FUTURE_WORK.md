# Future work and next-session handoff

Updated2026-10-07. Start with [current project status](PROJECT_STATUS.md).
The **Java virtual-thread load tester is delivered**, with its own Docker group,
14 passing tests,8 Postman requests/9 assertions and a locally verified100-call
10 RPS smoke. Read [Java tutorial](JAVA_LOAD_TESTER_TUTORIAL.md) and
[verification](../load-tester-java/docs/VERIFICATION.md). The earlier Node controlled
comparison remains retained; do not rebuild either from an older planning note.

At12:21 IST, APIs/gateway/PostgreSQL and Java/Node generator containers were
observed stopped and retained. PostgreSQL keeps its1 GiB limit and existing volume.
No application tests or workload were run during this documentation refresh.

Next pending work is the selected **Ankita checkout/private connectivity/finite
run**. Java automatic restart discovery, paired live-fault orchestration, shared
hold admission and movie group/payment load remain separate extensions. Redis
availability is deferred to **phase3**, with a proposed500 MiB allocation and
PostgreSQL remaining seat-ownership authority. No Redis/cache/SSE exists here yet.

## Earlier deliveries — 2026-10-06

Latest2026-10-06: [local separate Docker10 RPS smoke](DOCKER_LOAD_TEST_TUTORIAL.md)
passed100/100 fresh holds after PostgreSQL was raised to1 GiB. User deferred
Redis to **phase3**, for high-throughput/quick availability updates, with a future
500 MiB budget. No Redis service/cache/push added; database ownership remains
authoritative. Phase3 requires separate implementation selection; shared hold
admission and movie publishing/streaming remain unbuilt.

Latest2026-10-06: [checkoutable generic client module](HOLD_LOAD_TEST_TUTORIAL.md)
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
categories (default10/20/70), independent shows, atomic1-10-seat groups and
95/4.5/0.5 mock payment paths. Failed payment retains the original deadline/group;
a fresh key creates a fresh attempt. Read [tutorial](MOVIE_BOOKING_TUTORIAL.md),
[API](MOVIE_API_REFERENCE.md), [ADR](ADR_001_MOVIE_GROUP_BOOKING.md) and
[verification](MOVIE_VERIFICATION.md).

The selected later work is [thousands-user same-day simulation](MOVIE_LOAD_SIMULATION_PLAN.md).
The earlier **do not implement yet** override applied to the planning session;
later selections delivered the generic load clients. Read the
[hot-show availability and retry-storm plan](HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md).
Existing generic scenario2 protections and generic client comparison are available
for study. Availability streaming/shared admission still need separate selection.
Existing logical expiry is implemented; live client updates are not.
Future roles are selected: Abhishek hosts services; Ankita generates load over
Tailscale. Java tool/budgets are documented; private remote ingress and actual
Ankita execution remain pending. No sustained capacity run is recorded.

Other proposals remain separate:

- Frontend, browse/search filters, visual/adjacent seat maps and groups over10.
- Movie transition outbox/notifications; inherited generic outbox does not publish movie changes.
- Independent mock process, bounded movie admission and poison policy; current mock shares DB availability.
- Actual refund workflow, buyer authentication, callback verification and retention/idempotency policy.
- Catalog/layout/schedule edits with a defined policy for existing bookings.
- Show partitioning, waiting rooms, read caching and DB HA driven by measured needs.
- Original generic scenarios5-6, optimistic seats and a larger retry-storm study
  beyond the delivered finite generic clients remain proposals.

Repository is `D:/sd-book-my-show`, main branch, Maven artifact `sd-book-my-show`.
Earlier14 source snapshots use disclosed September15-October5 reconstructed
dates. Setup/movie work uses actual timestamps. Do not rewrite real evidence dates.
Publishing awaits a fresh empty GitHub repository URL. Do not reuse the original
Java workspace origin. Push without force and verify the remote SHA matches HEAD.

Addresses: A8130/B8131/gateway8132/PostgreSQL5553; optional generic provider8133.
Movie needs no provider container. Preserve original repositories/IntelliJ edits/
services and all data. No file/directory deletion or Maven clean/prune/reset without
explicit user permission. Check verification for final scheduled/manual stack state.

Historical movie handoff on2026-10-05: the standalone base+failover stack was running, both
legacy/movie workers enabled, movie controls off.68 tests,23 A/B runtime checks,
30 movie requests/60 assertions and75 generic requests/110 assertions passed.
Pending movie payments and confirmed ownership mismatches were both0 at that check. Resume
with the tutorial; implement sustained simulation only when the user selects it.
