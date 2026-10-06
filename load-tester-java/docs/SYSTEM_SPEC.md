# Finite virtual-thread booking generator

Purpose: apply the existing Spring Boot shortener generator's fixed-schedule
HTTP model to the generic ticket API in the Book My Show repository. See
[reference mapping](../PARITY.md) and [tutorial](../../docs/JAVA_LOAD_TESTER_TUTORIAL.md).

Control8135 is local; target is startup configuration. One active run or discovery
per process. A run performs one readiness read, one non-retried fresh fixture
creation, then schedules measured operations independently of response times.
No payment calls. HOLD hits POST /api/holds; AVAILABILITY reads the first100 seats;
MIXED selects with a reproducible seed. Seat number cycles through configured
inventory; smaller inventories deliberately produce terminal conflicts.

Invariants:

- Buyer/key/exact payload remain constant throughout a logical hold and discovery.
- No409/400 retries; timeout/408/5xx ambiguity cannot be turned into definite rejection.
- One client task at a time per logical operation; a task that fails to close stops
  the run and blocks further run/discovery admission until it closes.
- Initial scheduling uses no waiting queue. Concurrency saturation or100ms schedule
  lateness stops future arrivals; unoffered work stays NOT_STARTED.
- At most4 application attempts/operation; arrival-relative10s deadline. No redirects.
  JDK connection retry is disabled at application startup. Application calls are
  counted; the metric does not prove how many HTTP messages the server received.
- Only one separately admitted discovery, at most2 calls/unknown hold, with one10s
  discovery-phase deadline. No bulk budget reset or fresh key after ambiguity.
- Local100/remote200 initial operations, concurrency8/32, total run30/75s,
  64 KiB response cap,20 histories/process,85% heap guard,20-error streak stop.
- Local observer missing/stale/STOP stops arrivals/retries. Observer owns server
  CPU/memory/DB-pressure checks; generator's Docker quota bounds its own resources.

Fresh fixture admission is not idempotent. If its response is lost, the run fails
without retrying fixture creation. The fixture might exist and must be retained.
Retry controls are client behavior; no shared hold admission is added to A/B.

Each run persists manifest, operation identities, a forced pre-send journal,
attempt metadata, outcome identities and report. No raw HTTP bodies or token is
stored. Process crash does not automatically reload histories or admit discovery:
retain artifacts and study the earlier Node recovery workflow; do not regenerate
keys or assume NOT_STARTED from a missing completion report. Java discovery applies
to completed in-process UNKNOWN operations. Report files are ordinary writes;
the forced journal is the conservative evidence of potentially dispatched work.

Abhishek hosts services; Ankita runs final measurements. Local runs are an explicit
bounded exception. Remote observer files are not magically synchronized: operator
must watch Abhishek's metadata and use the stop API on Ankita if no shared control
file exists. No production auth, distributed generator coordination, HA, external
exactly-once, load capacity or SLO claim. Redis is future advisory availability;
PostgreSQL remains seat-ownership authority.
