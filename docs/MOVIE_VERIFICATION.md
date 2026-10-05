# Movie verification - 2026-10-05

Additive V5 in the independent repository. `mvn -B -ntp verify` passed68 tests,
0 failures/errors/skips:53 inherited regressions,13 real-PostgreSQL movie tests,
2 mock-plan tests. Executable jar rebuilt. Movie cases cover rounding/bounds,
HTTP validation, atomic rollback/races, cross-show/date independence, concurrent
schedule/turnaround conflicts, key replay, both success paths, failure-held groups,
fresh attempt history/no deadline extension, expiry/reassignment/late refunds,
receipt replay after simulated application response loss and stale lease tokens.

The10000-bucket unit check proves9500/450/50 decision intervals, not statistical
live-run proportions. The initial Spring repository proxy-field issue was fixed
before final passes. No application code in the original Java workspace changed.

Actual base+failover+movie config/build/start passed. Independent stack migrated
its new retained PostgreSQL through V1-V5. `node scripts/learn-movie.mjs` passed23
checks:32 A/B HTTP hold competitors produced1 complete winner/31 expected409s;
partial rollback, same-day/cross-show independence, payment failure/retained hold,
fresh key/history/retry success, first-call success, cancellation/refund/release,
gateway control404 and zero valid seat conflicts. This was finite correctness
testing, not a throughput or tail-latency benchmark.

Movie Newman6.2.2 passed30 requests/60 assertions,0 failures. The initial use
of Postman's reserved `data` identifier was corrected before that final run.
Final scheduled base+failover restored, movie study overlay omitted. Six explicit
checks passed: deterministic fixture400, manual control404 and an ordinary random
payment automatically reached CONFIRMED/SUCCEEDED without manual ticks. Original
generic Newman regression also passed75 requests/110 assertions,0 failures;
its polling request count varies between runs.

Artifact validation:77 required files,169 named requests,177 parsed scripts,
31 Markdown files,310 local links,0 broken links;34 historical absolute references
counted separately. JavaScript syntax and Git whitespace/connectivity checks pass.
Base plus failover/movie Compose validates; earlier base/provider/poison/outbox
configs validated during extraction. Docker images package the verified jar.

Final SQL confirms V1-V5 successful, pending movie payments0 and confirmed groups
with missing member ownership0. Runtime fixtures, receipts, failed/successful
payment history and data are retained. Default mode has automatic legacy/movie
workers enabled and movie controls off. Standalone services A8130/B8131/gateway8132/
PostgreSQL5553 remain running. Original ticket stack and all nine shortener services
remain running with unchanged uptime from this delivery's operations.

Raw ignored logs/reports are under `target/movie-*`; a compact committed record is
[movie evidence](evidence/MOVIE_BOOKING_2026-10-05.json). No file/directory deletion,
Maven clean, prune, data copy from live databases or unrelated-service operation.
New features are committed with actual dates. Fresh origin URL is still missing;
local commits are not publication evidence.

No thousands-user workload, browser/frontend, independent movie provider outage,
actual payment/refund, production authorization, movie notification outbox or HA/
SLO guarantee was verified. Original V1-V4 studies remain separate from movies.
