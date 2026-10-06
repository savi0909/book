# Hold-load client verification — 2026-10-06

Follow-up: [separate Docker10 RPS result](DOCKER_LOAD_TEST_TUTORIAL.md),100/100
fresh holds,zero SQL violations,28 Node tests inside Docker. PostgreSQL1 GiB;
Redis phase3 deferred. Historical checks below retain their original scope.

User-selected scope: build a checkoutable load-test module here; the user will
execute the final business comparison on Ankita. See the
[runbook/tutorial](HOLD_LOAD_TEST_TUTORIAL.md) and [module](../load-test/README.md).
Generic client/fault ingress/observer are implemented. Shared backend admission,
movie load workflows and remote experiment results remain separate/unbuilt.

## Actual local checks

Windows, Node24.11.0, Java21.0.9, Maven, Docker Desktop, PostgreSQL16 Testcontainers.
No Maven clean or retained-data cleanup.

| Command/check | Actual result |
| --- | --- |
| `node --test load-test/test/*.test.mjs` | 28 passed, no failures/skips |
| `mvn -B -ntp verify` | 69 passed, zero failures/errors/skips; packaged application |
| `mvn -B -ntp -Dtest=HoldLoadClientIntegrationTest test` after client changes | 1 real PostgreSQL client contract passed again |
| `node load-test/cli.mjs help` | CLI entry loads/prints commands |
| 10-second metadata-only observer smoke | Completed in11.86s; four project containers/DB/host metadata; no threshold stop; actual sampling slower than1s |
| `load-test/audit.sql` through existing project's PostgreSQL, read-only | All six violation counts0; selected synthetic run had no booking rows |

Observer/audit smoke is syntax/metadata evidence, **not** an executed hold
comparison. Observer artifacts are retained in `target/hold-observer-20261006-1505`.
First sample:16 DB connections,0 active/lock waits, a point-in-time observation.
Production Java, Compose services/configuration, scheduler state and retained
data were not changed during that initial smoke.

## Additional user-authorized local loader validation

The user subsequently allowed this machine's loader to validate functionality.
Explicit --local-validation permits loopback only, <=20 operations/arm,
8 concurrent,30s/arm. Ordinary final business runs still require Ankita.
Both local checks used the actual gateway/A/B/PostgreSQL stack and default-off
faults enabled only in temporary loopback ingress. No tailnet port exposed.

| Check | Actual outcome |
| --- | --- |
| Run `aba6f1c662b4ab96`,20 operations/arm | Each arm:28 attempts, amplification1.4,10 holds,10 seat409s,7 LAB_BUSY503s,1 lost response discovered by replay200; no unresolved/stops |
| Run `527340783f6cb6e2`,2 operations/arm,one policy attempt,loss every operation | Four committed responses lost; primary reports4 UNRESOLVED; separate CLI discovery uses4 calls/all replay200, resolving original bookings |
| SQL audits for both actual run IDs | All six violation counts0;20 and4 HELD rows respectively at audit time |
| Observer/control lifecycle | Both40s observers completed without threshold stop; ingress processes closed, active0; metadata/fixtures retained |
| Repeating policy/discovery phases | Both rejected by EEXIST markers before any additional HTTP |

[Machine-readable evidence](evidence/HOLD_LOAD_CLIENT_2026-10-06.json).
Raw metadata/config/manifests/checkpoints: `target/hold-local-validation-20261006`.
Four events/24 bookings retained; normal schedulers stayed enabled. No bodies
or tokens saved. No remote/performance conclusion follows from one local pair:
immediate ran first; warm-up/order/floors can dominate latency. Recovery attempts
are separately counted rather than omitted from amplification.

The added [Java test](../src/test/java/com/example/booking/HoldLoadClientIntegrationTest.java)
starts isolated Spring Boot/PostgreSQL with maintenance off and invokes the actual
Node transport/policy. For each policy: one finite pre-commit busy rejection,
lost committed201, replay200, then identical booking ID/creation/expiry, four
concurrent same-key replays, terminal seat409 and changed-input409. Both paths
use three policy attempts. Full Maven run preserves all original movie tests.

Node checks cover seeded schedules/jitter, distinct identities, floors, terminal
errors, attempt/deadline stop, malformed/ambiguous responses, full-body timeout,
body bounds, redirects, saturation/host guard, private scope/token/default-off
faults, observer thresholds and latency denominators. A fake HTTP test repeats
three finite pairs with alternating arm order; each arm has four operations,
seven attempts and one replay. These are client correctness tests, not PostgreSQL
load/capacity or Tailscale evidence.

## Pending on actual machines

- Prepare new retained events/transfer manifest to Ankita; start observer/scoped ingress on Abhishek.
- Verify tailnet/ACL/firewall and record direct/DERP path. Status showed Ankita offline; port8134 was not started or exposed during delivery.
- Run small matched pairs/bounded discovery on Ankita, retain metadata and audit actual run IDs after quiescence.
- Repeat fresh fixtures/alternating arm order before drawing performance conclusions.

No generator was installed/run remotely or run as sustained business load on
Abhishek. No p95 improvement, shared admission, movie load, HA or100K capacity
claim exists. Hikari pool wait and gateway/backend percentiles are not collected;
current actuator exposure has no metrics route. DB pressure samples can miss spikes.

No application route changed, so the existing generic API/Postman collection is
retained. No duplicate Postman/Newman business run was needed for this CLI addition;
the actual Node/PostgreSQL test exercises its retry/discovery contract. Final
artifact/link and whitespace results are recorded in worklog/machine evidence.

Origin remains absent; no push is claimed. A verified Git bundle in target supports
manual Ankita checkout; manifests/tokens are transferred separately. Client stage:
implemented/tested locally. Remote stage: pending learner run. Shared server
admission: unbuilt, separate selection. Resume artifacts without resetting journals.
