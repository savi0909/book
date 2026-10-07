@AGENTS.md

Latest: movie advance-booking journeys in the Java loader; PostgreSQL 3 GB/2 CPU;
[Ankita PostgreSQL over Tailscale](docs/ANKITA_POSTGRES.md) scripted, not yet run.
User asked for no further tests/load until requested.

Latest: [permanent PVR catalog + rolling window](docs/PVR_CATALOG_SCHEDULE.md).
303 PVR/INOX sites/1,971 screens (V7), shows today+3 auto-filled, past days purged,
holds beyond window409. Next selection: booking/cancellation simulation.

Current entry: [project status — 2026-10-07](docs/PROJECT_STATUS.md).
Java loader delivered; all service/generator containers currently stopped and
retained. Final Ankita run pending; Redis phase3 deferred; origin absent.

Latest selected loader: [Java virtual-thread module](load-tester-java/README.md)
and [tutorial](docs/JAVA_LOAD_TESTER_TUTORIAL.md), own Docker group/control8135.
14 Java tests,8 Postman requests/9 assertions,100/100 actual10 RPS holds passed;
SQL violations0. Node retained; final Ankita run pending; Redis phase3 deferred.

Latest: [separate Docker10 RPS smoke](docs/DOCKER_LOAD_TEST_TUTORIAL.md) passed
100 holds/zero violations. PostgreSQL1 GiB applied; Redis500 MiB availability
phase3 deferred. Own load Compose group, exited; fixtures/metadata preserved.

Current module: [hold comparison runbook](docs/HOLD_LOAD_TEST_TUTORIAL.md) and
[verification](docs/HOLD_LOAD_TEST_VERIFICATION.md). Built/tested locally; final
business run belongs on Ankita. Generic-only; shared admission/movie load unbuilt.
Preserve journals/fixtures/services and evidence boundaries.

Resume with [project context](PROJECT_CONTEXT.md) and [worklog](docs/WORKLOG.md).
They record the standalone movie contract, verified delivery and pending origin.

Current delivery: [movie group booking](docs/MOVIE_BOOKING_TUTORIAL.md),
[movie verification](docs/MOVIE_VERIFICATION.md) and [future work](docs/FUTURE_WORK.md).
User selected atomic groups and retaining the original hold after payment failure.
Movie/V5 domain is separate from the historical generic studies below.

Latest continuation: [transactional outbox scenario4](docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md).
Read current AGENTS/evidence. Atomic snapshot source + separate atomic inbox/effect
and leased ack, monotonic versions, retained history, no sends/external exactly-once.
Optional manual overlay; default scheduled mode restored. Stop after4;5–6 remain proposals.

Latest study: [poison-job isolation](docs/POISON_JOB_TUTORIAL.md), scenario3.
Read current verification/handover. Maximum2 keyed redrives, persistent UNKNOWN
quarantine and separate dependency classification; no budget/identity resets.
Default scheduled mode restored after manual study; provider8123 conflicts with
shortener C. Scenarios1–3 complete; stop instead of automatically starting4–6.

Read the linked local spec, guide, API, parity, verification and interview guide,
then canonical learning memory before work. Preserve originals and prior projects.

Current study: [API failover tutorial](docs/API_FAILOVER_TUTORIAL.md). Deliver one
scenario at a time with detailed explanations and 3–5 interview points per subtopic.
Scenario 1 is verified; provider isolation and poison-job handling follow separately.

Current delivery: [provider isolation tutorial](docs/PROVIDER_ISOLATION_TUTORIAL.md).
Scenario2 is verified; scenario3 follows in a separate session. Use the provider
overlay for scenario2 and default simulator for the original polling collection.
