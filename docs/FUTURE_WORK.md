# Future work and next-session handoff

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
Latest override: **do not implement yet**. Read the
[hot-show availability and retry-storm plan](HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md).
First study existing generic scenario2 protections; separately select the controlled
generic-hold retry comparison before building availability streaming/admission.
Existing logical expiry is implemented; live client updates are not.
Future roles are selected: Abhishek hosts services; Ankita generates load over
Tailscale. Tool/budgets/private ingress remain to be chosen. No sustained load run.

Other proposals remain separate:

- Frontend, browse/search filters, visual/adjacent seat maps and groups over10.
- Movie transition outbox/notifications; inherited generic outbox does not publish movie changes.
- Independent mock process, bounded movie admission and poison policy; current mock shares DB availability.
- Actual refund workflow, buyer authentication, callback verification and retention/idempotency policy.
- Catalog/layout/schedule edits with a defined policy for existing bookings.
- Show partitioning, waiting rooms, read caching and DB HA driven by measured needs.
- Original generic scenarios5-6, optimistic seats and general retry-storm harness are still proposals.

Repository is `D:/sd-book-my-show`, main branch, Maven artifact `sd-book-my-show`.
Earlier14 source snapshots use disclosed September15-October5 reconstructed
dates. Setup/movie work uses actual timestamps. Do not rewrite real evidence dates.
Publishing awaits a fresh empty GitHub repository URL. Do not reuse the original
Java workspace origin. Push without force and verify the remote SHA matches HEAD.

Addresses: A8130/B8131/gateway8132/PostgreSQL5553; optional generic provider8133.
Movie needs no provider container. Preserve original repositories/IntelliJ edits/
services and all data. No file/directory deletion or Maven clean/prune/reset without
explicit user permission. Check verification for final scheduled/manual stack state.

At this delivery's handoff the standalone base+failover stack is running, both
legacy/movie workers enabled, movie controls off.68 tests,23 A/B runtime checks,
30 movie requests/60 assertions and75 generic requests/110 assertions passed.
Pending movie payments and confirmed ownership mismatches are both0. Resume
with the tutorial; implement sustained simulation only when the user selects it.
