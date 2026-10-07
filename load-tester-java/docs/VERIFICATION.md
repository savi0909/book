# Verification — 2026-10-06

Status refresh2026-10-07: [current project status](../../docs/PROJECT_STATUS.md).
All business and generator containers are now stopped and retained. Results below
were executed onOctober6; no application tests/load were repeated for the refresh.

`mvn -B -ntp -f load-tester-java/pom.xml verify`:14 tests,0 failures/errors/skips.
Actual Spring management API and virtual-thread RestClient hit local HTTP stubs.
Checks cover fixed arrivals, loss-after-commit exact replay, separate discovery,
409 terminal behavior, Retry-After budgets/date floors, seeded jitter, saturation,
stop/drain, availability reads, config validation, incomplete response-body timeout,
observer role/target scope and active heartbeat expiry. Test fixtures are retained
under ignored target directories; no filesystem cleanup is invoked.

Initial real Docker smoke `a8c2da79661a4163`,2026-10-06T12:06:47Z:100 fresh holds
at10/s,100201,ten buckets of10,100 virtual-thread attempts,peak2 logical calls,
no discovery/conflicts/errors/unknowns. HTTP avg9.47ms/p508.82ms/p9514.10ms/
p9918.37ms. Six read-only SQL violation aggregates were0;100 HELD at audit.
Observer ran60s with no pressure stop. One event/100 bookings retained.
Small later task-closure/observer guards are covered by the final14-test suite;
final packaged runtime verification follows.

Final package smoke `ae2d849d95414eb1`,2026-10-06T12:13:46Z:100 scheduled/offered,
100201,ten buckets of10,100 virtual-thread attempts,peak2,in-flight/open HTTP0
at completion. No retries/errors/conflicts/unresolved. HTTP avg14.11ms/p508.65ms/
p9535.56ms/p99119.47ms/max145.32ms; wall9916ms. All six SQL audit aggregates0,
100 HELD at audit. Tail timing varies locally; no throughput or causal comparison
is inferred.90s observer completed without pressure stop.

Newman6.2.2 collection:8 requests/9 assertions,0 failures,10.5s. Its separate
run `4280e600e6b44bc1` retained one fresh event/hold; empty discovery sent0 calls,
second discovery rejected409, invalid config rejected400. SQL audit all0.
Total this delivery: three events/201 bookings across two smokes and Postman.
After runtime checks, finalization was made atomic with status reads so completed
state/report persistence/active-slot release publish together; the14-test suite
was rerun successfully and the packaged Docker image rebuilt.
Generator containers stopped and retained; SIGTERM shutdown exited143 with
Tomcat graceful shutdown complete. Original service group was healthy at that check,
PostgreSQL same container/volume/1073741824-byte limit. No Redis deployed.

See [tutorial](../../docs/JAVA_LOAD_TESTER_TUTORIAL.md) and
[metadata evidence](../../docs/evidence/JAVA_LOAD_TESTER_2026-10-06.json).
The backend itself is unchanged; prior69-test backend verification is historical,
not rerun for this independent generator build. Prior28 Node tests remain the
Node module's evidence. Remote Ankita/private gateway reachability, movie group
load, Java crash/restart discovery and high-throughput capacity remain unverified
or unimplemented. No assertion of performance improvement versus Node or new
PostgreSQL sizing is made from a single local smoke.
