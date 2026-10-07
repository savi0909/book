# SD Book My Show worklog

Actual delivery record. Reconstructed sprint dates are described in
[provenance](../TIMELINE_PROVENANCE.md), not treated as actual work dates here.
Current resume entry: [project context](../PROJECT_CONTEXT.md).

## 2026-10-05: independent repository and movie backend

Created `D:/sd-book-my-show` with fresh Git init, as requested. Imported14 actual
ticket-lab snapshots onto a disclosed September15-October5 study schedule;
verified their tracked content against source snapshots. Included production/test
code, migrations, provider stub, Compose/configs, scripts, Postman and tutorials.
No dependent workspace modules were required. Original repository/data preserved.

Standalone setup commit `1be6f82` changed artifact/Compose naming and host ports
to avoid the original lab, and added setup, sprint mapping and verification docs.
Initial extraction build passed53 tests. No new origin was supplied/configured.

User then selected a movie backend and explicitly chose atomic multiple-seat
reservations and retaining seats after payment failure until original expiry.
Movie implementation commit `6c2e57a` added V5,5-100 screens,200-500 seats,
configurable categories, dated shows, frozen prices, atomic1-10-seat groups,
durable mock retries and fresh user payment attempts with retained history.

Delivered [movie tutorial](MOVIE_BOOKING_TUTORIAL.md), [API](MOVIE_API_REFERENCE.md),
[ADR](ADR_001_MOVIE_GROUP_BOOKING.md), [verification](MOVIE_VERIFICATION.md),
Postman/manual overlay/runtime scripts and [future-work notes](FUTURE_WORK.md).
The [thousands-user simulation](MOVIE_LOAD_SIMULATION_PLAN.md) remains a saved plan.

Final validation:68 tests;23 A/B runtime checks; movie Newman30requests/60assertions;
generic Newman75requests/110assertions;6 scheduled-mode checks. No failed final
checks.32 competing group requests yielded1 complete winner and31 conflicts.
SQL found no pending movie payments or confirmed member ownership mismatches.
These results establish bounded correctness, not sustained capacity or production HA.

Standalone base+failover was left running with automatic workers and controls off,
all data retained. Original ticket and shortener services were not operated on.
Shared learning context/progress/decisions and the canonical ticket case were updated.

## 2026-10-05: local context and agent handoff

User requested a worklog/project-context update and AGENTS.md if missing.
Confirmed AGENTS.md already exists. Added [local project context](../PROJECT_CONTEXT.md)
and this worklog; linked them from AGENTS.md, CLAUDE.md, README and future handoff.
Current movie rules take precedence over inherited generic study notes.

Validation for this documentation update: project artifact/link validator and Git
whitespace checks; application tests were not rerun because executable behavior
is unchanged. Local documentation changes are committed with actual timestamps.
Push remains pending the fresh origin URL; no publication is claimed.

## 2026-10-05: hot-show availability and retry analysis only

User asked about100K visitors for33-500 seats, then live availability and expiry.
The subsequent explicit instruction **do not implement yet** supersedes the
implementation request. Application files were not changed; no tests/load/network
configuration or service operations were run for this documentation task.

Added [hot-show availability/retry plan](HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md)
and linked README/context/AGENTS/future-work/simulation handoff. Source inspection
confirmed existing DB-time logical expiry, owner-checked cleanup, and the missing
live feed/general hold-traffic controls. Distinguished inherited generic scenario2
backoff/budget/breaker/slots/backlog protections from V5's separate movie mock.

Saved the requested sequence: study scenario2 first, later a controlled generic-hold
immediate-versus-jittered retry comparison. Movie snapshot/SSE fan-out and admission
remain proposals. Saved machine roles: Abhishek hosts actual services; Ankita sends
business load over Tailscale. Loopback ingress/reachability/tool/resource budgets
are unresolved; no connection or100K capacity claim.33 seats is a conceptual case;
current screen constraints remain200-500.

Validation: artifact/local-link validator and Git whitespace checks for documentation.
Artifact check passed:77 required files,169 named requests,177 script blocks,
34 Markdown files,365 local links and0 broken links. Whitespace check passed.
Shared canonical context/progress/decisions updated locally; that reference tree
has no Git metadata. Standalone task documents committed with actual timestamps;
publishing remains pending a fresh origin URL.

## 2026-10-06: scenario2 protection study

User selected study of existing scenario2 before building the controlled hold-retry
comparison. Added [reading lesson](SCENARIO_02_PROTECTION_STUDY.md), linked from
README/detailed tutorial, and updated local context/agent handoff. Traced generic
checkout admission/replay, durable claim, provider slots/deadlines, jittered next_at,
automatic dispatch budget, breaker/probes/generations, receipt application and
late-success refunds. Distinguished generic protections from V5 movie behavior.

Inspected implementation, migration, integration-test assertions, standalone stub,
overlay and historical verification. No application behavior/configuration changed;
no tests, load or fault experiments executed. Historical test evidence stays dated
2026-10-04. Study completion means explanation delivered, not mastery established.
The hold harness/availability stream/admission remain proposed. Abhishek-services/
Ankita-load-over-Tailscale roles preserved; no networking operation performed.

Validation: artifact/local-link validator and staged Git whitespace checks.
Artifact check passed:77 required files,169 requests,177 script blocks,35 Markdown
files,381 local links and0 broken links. Whitespace checks passed.
Shared canonical learning records updated locally. Local commit uses actual time;
fresh standalone origin is still absent, so publication remains pending.

## 2026-10-06: fresh-session implementation handover

User requested a handover prompt to build the missing protections next session.
Created [hold retry/admission handover](HANDOVER_HOLD_RETRY_BUILD.md), linked from
README/context/AGENTS/future work. The prompt selects a finite generic-hold client
comparison, then separately measured admission. It preserves Abhishek-services/
Ankita-Tailscale-load topology, existing movie semantics and already implemented
generic provider protections. Live SSE/waiting-room/100K work remain later.

This delivery only writes the handover; no implementation, load, networking or
service changes. Includes scope, source-reading order, implementation acceptance,
actual-evidence boundaries, full learning kit and commit/push requirements.
Documentation validation: artifact/local-link and Git whitespace checks. Updated
canonical resume records locally. Standalone origin absent; publication pending.
Artifact check passed:36 Markdown files,393 local links and0 broken links;
77 required files,169 requests and177 script blocks validated. Whitespace passed.


## 2026-10-06: checkoutable client plus authorized local validation

User selected building the load-test module at D:/sd-book-my-show and will run
the final business comparison on Ankita. User additionally authorized bounded
local loader functionality checks on Abhishek. Implemented dependency-free
Node >=22 immediate/seeded-jitter generic holds, equivalent arrivals/distinct
fixtures, four total attempts/ten-second deadline, bounded discovery/crash
journals/markers, default-off scoped token ingress and metadata observer/SQL audit.
Runbook: [hold client tutorial](D:/sd-book-my-show/docs/HOLD_LOAD_TEST_TUTORIAL.md);
[evidence](D:/sd-book-my-show/docs/HOLD_LOAD_TEST_VERIFICATION.md).
28 Node tests/69 Maven tests passed, including actual Node with isolated
Spring Boot/PostgreSQL committed-loss discovery; movie regressions preserved.
Local pair:20 operations/arm,28 attempts/arm,10 holds/10 seat conflicts/one replay
per arm. Tiny exhaustion run:4 unresolved committed holds,4 discovery200s. Both
SQL audits zero violations; repeat phase markers reject before HTTP. Four events/
24 bookings retained; temporary loopback ingresses closed, Compose unchanged.
No tailnet exposure/remote execution/capacity claim. Final sustained runs remain
Ankita-origin; shared server admission/movie load/SSE/100K unbuilt. Origin absent;
local verified commit and Git bundle transfer, no push claim. Canonical tree has
no Git metadata; these records are updated locally.

Final artifact check:95 required files,169 requests,177 script blocks,39 Markdown
files,434 local links,0 broken. Git whitespace checks passed. No Maven clean,
file/data deletion, original lab operation or remote generator installation.


## 2026-10-06: separate Docker10 RPS / PostgreSQL1 GiB / defer Redis

User corrected run origin to this machine, requested a separate Docker group,
PostgreSQL1 GiB and then deferred Redis to phase3 for quick availability/high
throughput.500 MiB is the future Redis budget, not a current allocation.
Delivered [runbook/result](D:/sd-book-my-show/docs/DOCKER_LOAD_TEST_TUTORIAL.md).
Project sd-book-my-show-load-test uses256 MiB/0.5 CPU and existing booking network.
Actual run d95d073367e9dae0:100 fresh generic holds,10 every second for ten
buckets,100201 successes,zero retries/conflicts/unresolved/stops; avg7.95ms,
p507.04ms,p9511.63ms,p9921.47ms Docker HTTP latency. All SQL violations0.
28 Node tests passed in Docker; Java unchanged, prior69 Maven tests not rerun.
PostgreSQL updated live to1073741824-byte limit; same container/retained volume,
healthy, no restart. Base Compose persists1g RAM/2g combined memory+swap.
Loader exited0; observer completed40s, no stop. Containers/fixtures/data retained,
no file/volume deletion. Interrupted pre-Docker setup retained2 unused events and
ran no holds. Current smoke retained2 events/100 bookings. No Redis/push/cache
added; PostgreSQL ownership remains authoritative. Final longer runs on Ankita
remain pending; current local result is not a capacity or1 GiB improvement claim.
Origin absent; local scoped commit/bundle, no push claim.

Follow-up final validation:100 required artifacts,169 requests,177 script blocks,
40 Markdown files,449 local links,0 broken; both Compose configs/whitespace passed.
No application code changed; Docker unit tests28/28 and actual100-request smoke
supply task validation. Canonical records updated locally; origin still absent.

## 2026-10-06: Spring Boot virtual-thread Book My Show load tester

User selected the existing URL-shortener Java generator model. Inspected actual
D:/java-projects/url-shortener-load-test LoadRunService/ClientConfig/LoadController,
then delivered standalone load-tester-java inside D:/sd-book-my-show. Java21,
Boot3.5.16, virtual-thread blocking RestClient, fixed initial arrivals, finite
HOLD/AVAILABILITY/MIXED, stable buyer/key/payload retries, terminal409 conflicts,
whole-exchange timeout/task closure, seeded jitter/Retry-After, separate capped
unknown discovery, observer/heap/concurrency guards and metadata-only artifacts.
Own Compose project sd-book-my-show-java-load-test, control8135 loopback,
384 MiB/0.5 CPU/192 MiB heap. Node implementation and history retained.
Full tutorial/spec/API/guide/parity/Postman/local agent entries included:
[D:/sd-book-my-show/docs/JAVA_LOAD_TESTER_TUTORIAL.md](D:/sd-book-my-show/docs/JAVA_LOAD_TESTER_TUTORIAL.md).

Actual validation:14 Java tests,0 failures/errors/skips. Newman8 requests/
9 assertions,0 failures. Initial real Docker smoke a8c2da79661a4163 and final
package ae2d849d95414eb1 each100201 at10/s, ten buckets of10,100 virtual-thread
attempts,zero retries/errors/conflicts/unknowns; SQL six violation aggregates0.
Final HTTP avg14.11ms/p508.65ms/p9535.56ms/p99119.47ms, wall9916ms; local tail
variation recorded, no Java-versus-Node/capacity claim. Observers60/90s completed
without stops. Postman separate4280e600e6b44bc1 retained1 hold/empty discovery.
Three events/201 bookings retained; generator containers stopped/retained,
SIGTERM143/graceful shutdown. Normal four-service group healthy, PostgreSQL
same container/volume/1 GiB; Redis phase3 deferred, no other service changes.
Java backend unchanged (prior69-test evidence not rerun). Artifact checks:
124 files,177 requests,185 scripts,49 Markdown files,496 links,0 broken.
Both Java Compose configs and whitespace checks passed. Final Ankita private
reachability/run pending; no Tailscale changes. Java restart discovery/Node
manifest compatibility/movie group load/live faults/shared hold admission
remain separate work. Canonical records updated locally, origin absent.

## 2026-10-07: documentation and project-status refresh

User requested updated docs and project status after the Java generator delivery.
Added [current status](PROJECT_STATUS.md) with delivered generic/movie features,
Java-versus-Node scope, dated verification, current runtime, pending Ankita work,
shared admission/extensions and Redis phase3. Updated README/context/agent entries,
future work, standalone setup, resume/hold-retry handovers and Java module entry/
verification docs. Superseded older unbuilt-client/teaching-only statements while
preserving historical evidence and proposal boundaries.

Read-only runtime observation at2026-10-07T06:51:20Z (12:21 IST): API A/B,
gateway and PostgreSQL stopped, same containers/data retained. PostgreSQL remains
1073741824-byte limit with sd-book-my-show_booking-data. Both Java and Node
loader groups stopped/retained. No services were started/stopped/reconfigured,
no application tests or loads executed, no files/volumes deleted by this refresh.
[Container metadata](evidence/PROJECT_STATUS_2026-10-07.json) records the snapshot;
no new database-integrity result is inferred while PostgreSQL is stopped.

Implementation commit8f9fd03 and October6 results remain unchanged: Java14 tests,
Newman8 requests/9 assertions, real100/100201 at10 RPS, SQL violations0. Backend
69-test and Node28-test results remain separately dated prior evidence. Final
Ankita/private gateway execution pending; Redis500 MiB advisory availability
budget remains a phase3 proposal. Markdown links/artifact checks and whitespace
validation passed; no functional suite rerun for these documentation-only edits.
Origin absent; local documentation commit and verified transfer bundle. Canonical
learning records updated locally (reference tree has no Git metadata).

## 2026-10-07: permanent PVR catalog and rolling show window

User requested permanent PVR multiplex/screen/show data for future
booking/cancellation simulation, and chose:
- real names with generated layouts
- a today+3 window
- whole-day purge of past shows, bookings and payments
- a synthetic film slate

They asked to implement directly from a checklist (no spec document).

A Sonnet research agent compiled 303 properties in 77 cities from district.in city
pages. No source stated screen counts.

Added:
- `V6` schema
- `V7` generated data
- `scripts/generate_pvr_catalog.py`
- `MovieScheduleMaintainer`
- the hold window rule (`SHOW_NOT_YET_OPEN`)
- `MovieScheduleIntegrationTest`

Results:
- `mvn -B -ntp verify` passed 74 tests.
- The live stack was started (the PostgreSQL container was recreated; the volume
  was retained).
- The first tick added 40,333 shows / 14.25M seat rows in 7.8 minutes and purged
  1 past fixture show with its 2 bookings, 3 payments and 1 refund-required.
- Live audit: all violation counts were zero; the database is 1.7 GB.

Services were left running. Booking/cancellation simulation is not built.

## 2026-10-07: movie advance-booking simulation and Ankita PostgreSQL scripts

Added two browse endpoints:
- `GET /api/movie/multiplexes?city=`
- `GET /api/movie/now-showing`

Added the loader's MOVIE journey runner, using a shared run slot with the generic
runner. It was validated with 19 loader tests and 75 backend tests.

Live runs and what they found:
- Before any load, the observer refused on SERVER_CPU. Investigation traced this
  to JIT compiling every maintainer batch; fixed as above.
- The observer then refused on host memory. The user approved stopping three
  unrelated stacks.
- The first smoke exposed the checkout 202/201 mismatch; fixed, and the stub now
  returns 202.
- The second smoke passed.
- The user stopped the main run at 537 journeys and asked for no more tests.

PostgreSQL was raised to 3 GB / 2 CPUs at the user's request.

The Ankita PostgreSQL plan (`infra/ankita/*`, `compose.remote-db.yml`,
`scripts/use-*-db.ps1`, [runbook](ANKITA_POSTGRES.md)) was written and
statically validated only (compose config, PowerShell parse). It was not executed:
Ankita was offline, and the user asked for no runs.

