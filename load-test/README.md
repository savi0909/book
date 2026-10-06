# Hold retry load-test module

Latest user-selected implementation: [Java Spring Boot virtual-thread tester](../load-tester-java/README.md),
adapted from the URL-shortener generator. This Node module and its evidence remain
retained for the existing scoped-fault/two-arm/crash-discovery study.

Latest: [separate Docker group/single-arm10 RPS smoke](../docs/DOCKER_LOAD_TEST_TUTORIAL.md).
Run completed locally at user request. This fixed smoke is independent of the
two-arm retry comparison; final remote measurements still belong on Ankita.

Dependency-free Node >=22 client for the existing **generic** `/api/holds` API.
Run business comparisons and discovery on **Ankita**; Abhishek hosts services,
creates retained fixtures, runs the optional scoped ingress and observes metadata.
User-authorized local functionality checks use --local-validation with hard
small-run bounds and a loopback ingress. Final business measurements stay on Ankita.

Start with the [full runbook and code tutorial](../docs/HOLD_LOAD_TEST_TUTORIAL.md).
See [actual checks and pending remote execution](../docs/HOLD_LOAD_TEST_VERIFICATION.md).

```powershell
# From the repository root, on either machine. Fake HTTP unit tests only.
node --test load-test/test/*.test.mjs
node load-test/cli.mjs help
```

No npm installation, Maven parent changes, backend deployment or payment workload
is required to run the client. Maven verification additionally runs one small
client contract test against isolated Spring Boot/PostgreSQL and requires Node.

The [small configuration](config.small.json) schedules 20 logical operations/arm,
10 seats/fixture, at 100ms intervals. Four total attempts/operation and a ten-second
arrival-relative deadline bound the retry policy. Two independent event IDs and
keys keep the second arm from becoming a replay of the first.

Every run gets new fixture/output directories. Do not change a fixture's recorded
config or remove its `.started` markers to rerun it. Preserve uncertain outcomes;
use the separate, capped discovery phase after the original runner exits.

The optional ingress exposes only exact fixture holds on a literal tailnet IPv4,
requires an environment token, has no HTTP management route and expires within
600 seconds. Faults default off. The observer must be running first. No request
or response bodies are persisted. Shared backend admission and movie workflows
remain subsequent work.
