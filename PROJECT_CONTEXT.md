# SD Book My Show project context

Updated2026-10-07. Start with [current project status](docs/PROJECT_STATUS.md), then read
[AGENTS.md](AGENTS.md), [worklog](docs/WORKLOG.md),
[movie tutorial](docs/MOVIE_BOOKING_TUTORIAL.md) and
[movie verification](docs/MOVIE_VERIFICATION.md).

## Current runtime and delivery boundary — 2026-10-07

Documentation/status refresh only. Repository was clean at implementation commit
8f9fd03; origin remains absent. Actual inspection at12:21 IST found API A/B,
gateway and PostgreSQL stopped; Java and Node generator containers also stopped
and retained. PostgreSQL is the same container with its1 GiB limit and retained
booking-data volume. No services were started, no workload/application tests were
run, and no data was deleted. [Observation](docs/evidence/PROJECT_STATUS_2026-10-07.json).
October6 runtime/test evidence below is historical. Java load tester is delivered;
final Ankita/private gateway execution remains pending. Redis is deferred phase3.

## Latest implementation: Java virtual-thread loader — 2026-10-06

Superseding generator selection: user requested Java/Spring Boot like the actual
`D:/java-projects/url-shortener-load-test`. Delivered [Java module](load-tester-java/README.md)
and [tutorial](docs/JAVA_LOAD_TESTER_TUTORIAL.md): independent Java21/Boot3.5.16
POM, virtual-thread RestClient, fixed arrivals, finite hold/read/mixed workloads,
bounded retries/unknown discovery and metadata-only reports. Own Docker project
sd-book-my-show-java-load-test,384 MiB/1 CPU (2026-10-07), management8135 loopback.
14 tests and Newman8 requests/9 assertions passed. Final real10 RPS100-call smoke
100201, all SQL violations0, avg14.11ms/p9535.56ms/p99119.47ms. Earlier Java smoke
100201 plus Postman1 hold also retained: three events/201 bookings total here.
Java containers stopped/retained, ordinary backend healthy at that delivery, no PostgreSQL/Redis
change. Node implementation preserved. Final Ankita reachability/load pending.
Java restart recovery and movie group/payment load are not implemented.

## Earlier Docker smoke and resource delivery — 2026-10-06

User corrected run origin to this machine, selected a separate Docker load group
and PostgreSQL1 GiB. Delivered [runbook/result](docs/DOCKER_LOAD_TEST_TUTORIAL.md):
project sd-book-my-show-load-test,256 MiB/0.5 CPU,100 holds at10/s,one attempt,
no faults.100201 successes,zero errors/conflicts/unresolved; all SQL violations0.
Avg7.95ms/p9511.63ms/p9921.47ms local Docker HTTP timings, not Ankita/capacity.
Loader exited0; server group was healthy at that delivery, PostgreSQL same container/volume
with verified1 GiB limit. Persistent compose memory1g/combined memory+swap2g.
Redis deferred explicitly to phase3 for quick availability/high-throughput study;
500 MiB future budget. No Redis/movie push/cache deployed.28 Node tests passed
in Docker; Java unchanged, prior69 Maven tests not rerun for this follow-up.

## Retained Node comparison delivery — 2026-10-06

User selected building the module here and will run it on Ankita. Implemented
Node >=22 generic `/api/holds` immediate/seeded-jitter comparison: matched arrivals,
distinct fixtures, four total attempts/ten-second deadline, bounded discovery,
crash journals. Optional scoped/token ingress has default-off busy/committed-loss
faults, finite lifetime and observer heartbeat/STOP closure. Metadata observer and
read-only SQL audit included. [Tutorial](docs/HOLD_LOAD_TEST_TUTORIAL.md),
[verification](docs/HOLD_LOAD_TEST_VERIFICATION.md).
28 Node tests/69 Maven tests passed, including actual Node/Spring/PostgreSQL
contract, rerun after client changes. Metadata observer/audit smoke passed.
User then authorized local loader checks: one20-operation/arm pair (28 attempts,
10 holds/10 conflicts per arm) and one2-operation/arm exhaustion/discovery check
(4 uncertain commits discovered with4 replay calls) passed against this stack.
Both SQL audits had zero violations. Four events/24 bookings retained. Temporary
loopback8134 ingress closed; no remote/sustained run, tailnet/Compose change.
Observed Abhishek100.103.238.2/Ankita100.84.247.65 (offline); recheck.
Remote reachability/comparison pending. Shared admission/movie load/SSE/100K
unbuilt. This narrow selection supersedes older teaching-only client notes below.
No origin/push; a Git bundle supports manual checkout.

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

The last runtime verification used **base+failover**, automatic legacy/movie workers
enabled and movie study controls off. On2026-10-07 the service and generator
containers were observed stopped with data retained; see the status page.
Only operate on this project's explicitly selected Compose group.

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

User requested a fresh-session build prompt on2026-10-06. Saved
[controlled hold retries and admission handover](docs/HANDOVER_HOLD_RETRY_BUILD.md).
The prompt's creation was documentation-only. Later user selections delivered
the Node comparison and then the Java virtual-thread generator. Resume those
implementations rather than rebuilding them. The broader shared-admission and
movie SSE/waiting-room/100K scope still needs an explicit selection.

User selected the scenario2 study on2026-10-06. Delivered
[source-reading lesson](docs/SCENARIO_02_PROTECTION_STUDY.md) with request trace,
protection boundaries, evidence pointers and prediction exercises. This records
an explanation, not learner mastery or a new runtime verification. The later
generic client implementation is delivered; shared server admission remains unbuilt.

Historical teaching-only direction on2026-10-05 preceded the later loader builds.
The planning source is the
[100K hot-show, live availability and retry-storm plan](docs/HOT_SHOW_AVAILABILITY_AND_RETRY_PLAN.md).
The generic hold retry clients now exist. Movie SSE/snapshot fan-out, waiting room and shared
hold admission are proposals, not delivered features. Existing database-time
expiry already permits safe rebooking independently of delayed durable cleanup.
The33-seat example is conceptual; screen constraints remain200-500.

Selected later work: [same-day thousands-user simulation](docs/MOVIE_LOAD_SIMULATION_PLAN.md).
Selected machine roles: **Abhishek hosts actual services; Ankita generates business
load over Tailscale**. Java is the selected generator, with finite documented
resource/workload bounds; private remote gateway access remains pending. Published
movie ports currently bind loopback;
tailnet connectivity/reachability has not been verified or configured. No sustained
workload has been run. Other proposals are in [future work](docs/FUTURE_WORK.md).
Do not start additional scenarios/features solely because they appear there.

No origin is configured as of this context update. Await the user's fresh empty
GitHub repository URL, then push main without force and verify remote HEAD.
Do not reuse the original workspace origin. Local commits do not establish publication.

Preserve unrelated edits and data. Never delete files/directories or run Maven
clean/reset/prune without explicit user permission. Teaching requests should update
the relevant docs with walkthroughs/exercises and links; do not silently expand code scope.
