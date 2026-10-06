# SD Book My Show project context

Updated2026-10-06. Start here when resuming this repository, then read
[AGENTS.md](AGENTS.md), [worklog](docs/WORKLOG.md),
[movie tutorial](docs/MOVIE_BOOKING_TUTORIAL.md) and
[movie verification](docs/MOVIE_VERIFICATION.md).

## Repository and learning purpose

Independent Java movie-booking backend at `D:/sd-book-my-show`, initialized with
a fresh Git repository at the user's request. Maven artifact `sd-book-my-show`,
Java21, Spring Boot3.5.16, PostgreSQL/JDBC/Flyway, package `com.example.booking`.
No dependent workspace modules, inherited workspace parent, Redis or broker.

Source was the tracked `ticket-booking-lab` folder from the original Java workspace.
That project, its repository, IntelliJ edits and retained services/data are preserved.
The generic ticketing endpoints and their V1-V4 studies remain available in this
copy, independently of the V5 movie domain.

The first14 imported snapshots have disclosed reconstructed author/committer
dates spanning September15-October5. They do not establish three weeks of actual
development. Source SHAs/real dates are retained in commit messages and
[mapping](docs/HISTORY_RECONSTRUCTION.json). See [provenance](TIMELINE_PROVENANCE.md)
and [sprint plan](docs/THREE_WEEK_SPRINT_PLAN.md). New work uses actual timestamps.

## Accepted user requirements

- Each multiplex has5-100 screens; every screen has200-500 seats.
- Screens have two or three categories. Default A10%, B20%, C70%; configurable
  positive percentages sum to100. Whole-seat rounding remainder goes to the final category.
- Shows freeze inventory/prices independently. The same seat number can be sold
  in different shows; scheduling rejects screen runtime/turnaround overlaps.
- Multiple selected seats are reserved atomically. Current request bound is1-10.
- Mock plans:95% succeed first call, another4.5% succeed on one logical retry,
 0.5% fail after that retry. These percentages apply to independent payment attempts.
- After definite failure, retain the entire group until its original expiry.
  A new user payment key creates a new attempt; it never extends that deadline.
- Thousands-user booking across same-day shows is explicitly later work.

## Implemented correctness boundaries

V5 adds movie catalog, show seats, immutable group members, group bookings,
payment histories, receipt identities and audit. Inventory mutations acquire
all member seat locks in ascending order, then booking, then payment when needed.
One transaction reserves all seats or none. PostgreSQL time determines validity;
the hold deadline is bounded by show start. Release clears only this owner's pointers.

Payments use durable intent IDs/keys, one unresolved intent per booking,5s leased
claims and token/time-checked application. Mock receipts commit separately and
deduplicate `(payment_id,provider_step)`. Automatic retry retains the payment ID;
fresh user payment creates a new ID/payment number and preserves failed history.
Response loss requires discovery with the original key, not an immediate new attempt.

Late success cannot steal reassigned seats; it records a refund obligation.
The movie mock is in-process and shares PostgreSQL availability. There is no real
payment/refund, production authentication, frontend, independent provider HA or
movie notification outbox. Generic outbox/poison guarantees do not automatically
apply to V5. Read [ADR](docs/ADR_001_MOVIE_GROUP_BOOKING.md) for the domain separation.

## Last verified delivery

Movie implementation commit: `6c2e57a` (2026-10-05). Standalone setup: `1be6f82`.

| Check | Result recorded on2026-10-05 |
| --- | --- |
| Maven verify |68 tests;0 failures/errors/skips |
| New movie tests |13 PostgreSQL integration tests plus2 mock-plan tests |
| Actual A/B runtime |23 checks;32 competing group holds produced1 winner/31 expected conflicts |
| Movie Newman |30 requests/60 assertions;0 failures |
| Generic regression Newman |75 requests/110 assertions;0 failures; polling count varies |
| Default scheduled-mode checks |6 checks passed, controls disabled, ordinary payment resolved automatically |
| Final SQL |V1-V5 successful; pending movie payments0; confirmed member ownership mismatches0 |

These are bounded correctness results, not measured thousands-user capacity or an
SLO. Runtime fixtures and evidence are retained. See
[committed evidence](docs/evidence/MOVIE_BOOKING_2026-10-05.json) and verification.

## Local operation

Last verified stack was running in **base+failover**, automatic legacy/movie workers
enabled, movie study controls off. Check actual Docker/HTTP state before relying
on that historical observation. Only operate on Compose project `sd-book-my-show`.

| Service | Host address |
| --- | --- |
| API A | localhost:8130 |
| API B | localhost:8131 |
| Gateway | localhost:8132 |
| PostgreSQL | localhost:5553 |
| Optional inherited provider | localhost:8133; not needed for movie mock |

Application containers listen8105; provider container8121. Host-only API requires
`PORT=8130`; JDBC defaults to5553. Volumes are separately scoped and retained.

Normal configuration: `compose.yml` + `compose.failover.yml`. Optional
`compose.movie.yml` enables deterministic buckets/manual controls and disables
both legacy/movie loops. Manual reconcile is replica-only/404 through gateway.
Restore normal mode by omitting that overlay. Read
[standalone setup](docs/STANDALONE_SETUP.md) before starting/stopping services.

## Next work and publishing

User selected the scenario2 study on2026-10-06. Delivered
[source-reading lesson](docs/SCENARIO_02_PROTECTION_STUDY.md) with request trace,
protection boundaries, evidence pointers and prediction exercises. This records
an explanation, not learner mastery or a new runtime verification. General hold
retry comparison is still unbuilt and needs a separate implementation selection.

Latest direction on2026-10-05: **do not implement yet**. Current study is the
[100K hot-show, live availability and retry-storm plan](docs/HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md).
Study generic scenario2 protections first, then separately select the controlled
generic-hold retry comparison. Movie SSE/snapshot fan-out, waiting room and shared
hold admission are proposals, not delivered features. Existing database-time
expiry already permits safe rebooking independently of delayed durable cleanup.
The33-seat example is conceptual; screen constraints remain200-500.

Selected later work: [same-day thousands-user simulation](docs/MOVIE_LOAD_SIMULATION_PLAN.md).
Selected machine roles: **Abhishek hosts actual services; Ankita generates business
load over Tailscale**. Choose tool/resource budget, workload shape and scoped private
ingress before implementation. Published movie ports currently bind loopback;
tailnet connectivity/reachability has not been verified or configured. No sustained
workload has been run. Other proposals are in [future work](docs/FUTURE_WORK.md).
Do not start additional scenarios/features solely because they appear there.

No origin is configured as of this context update. Await the user's fresh empty
GitHub repository URL, then push main without force and verify remote HEAD.
Do not reuse the original workspace origin. Local commits do not establish publication.

Preserve unrelated edits and data. Never delete files/directories or run Maven
clean/reset/prune without explicit user permission. Teaching requests should update
the relevant docs with walkthroughs/exercises and links; do not silently expand code scope.
