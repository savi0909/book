# Future thousands-user, same-day simulation

Saved plan, not an executed load test. Start with [movie tutorial](MOVIE_BOOKING_TUTORIAL.md).

At5 screens x200 seats x4 shows/day, fixture inventory is4000 show-seats across20
shows. At100 screens x500 seats x4 shows, it is200000 show-seats. These are capacity
arithmetic, not measured limits. Seed distinct future, non-overlapping shows and
retain each run's IDs/results. Use only the new project's data/services.

Increase workflows through50/200/500/1000/2000 simultaneous users after previous
levels pass. Users are not requests/s. Begin with uniform distribution over shows,
then80% on one hot show, then deliberate overlapping groups and duplicate-key
requests. Separate dependency-failure experiments from steady-state load.

Each user browses, holds1-4 seats, starts one payment key and polls with bounded
backoff.409 seat conflicts are expected business outcomes, reported separately
from503/timeouts. Unknown responses replay the original key. A definite FAILED
payment may lead to one deliberate fresh user attempt while the hold is live.

Payment denominators are independent attempts, not users/seats/bookings/HTTP
calls. At the95/4.5/0.5 probabilities, expected logical provider calls are1.05 per
attempt before recovery redispatch.10000 attempts have expected9500 first/450
retry/50 failed plans; samples fluctuate. Fresh user attempts add to the denominator.
Run normal automatic mode; forced buckets are correctness fixtures, not statistics.

Report per-second GET/hold/checkout/poll rates, average/p50/p95/p99, status counts,
timeouts, active clients, pending payment count/age, logical receipts, dispatches,
expirations/refunds, DB connections/lock waits and CPU/memory. Identify end-to-end
versus gateway timing. After quiescing clients/workers, audit no overlapping valid
owners, all confirmed groups own all members, keys are unique and logical receipt
identity is unique. Shared stats are advisory separate reads, not one DB snapshot.

Current JDBC pool8/API, servlet threads32, bounded lock/transaction deadlines,
HAProxy maxconn256 and20-payment batches/200ms are lab choices. Shared scheduler
and DB work reduce actual drain rate. They may bottleneck before1000 users.
One host/one PostgreSQL remains; no production HA/SLO/throughput claim exists.

Choose the generator host/tool and resource budget before implementation. Ankita's
earlier sole-load-origin rule applies to URL shortener; no movie generator host is
selected. Do not auto-start this workload. See [future work](FUTURE_WORK.md).
