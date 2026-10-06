# Loader management API

Local default base: `http://127.0.0.1:8135`. No authentication; keep the published
management listener loopback. Target, role and local exception are startup config,
not arbitrary destinations supplied in requests. Unknown JSON fields return400.

| Route | Result |
| --- | --- |
| POST /load/runs |202 `{runId,status}`; async finite run |
| GET /load/runs |200 list of run ID/state strings, max20 histories/process |
| GET /load/runs/{id} |200 live counters/normalized request/event ID/final report;404 absent |
| POST /load/runs/{id}/stop |200 status; stops new work, admitted calls drain to their deadline |
| POST /load/runs/{id}/discover |202; once per completed run, unknown holds only, separate budget |
| GET /actuator/health |200 loader process health; target checked separately on start |

POST example:

```json
{"mode":"HOLD","rate":10,"seconds":10,"seats":100,"policy":"NONE","maxAttempts":1}
```

| Field | Default | Bounds/meaning |
| --- | --- | --- |
| mode | HOLD | HOLD, AVAILABILITY, MIXED |
| rate |10 | Initial operations/s, local1..10, remote1..100 |
| seconds |10 | Local1..10, remote1..60; total operations local<=100/remote<=200 |
| seats |100 |1..200 fresh generic event seats; cycling makes hot-seat contention |
| holdPercent |20 |0..100, seeded choice in MIXED only |
| policy | NONE | NONE, IMMEDIATE, JITTER |
| maxAttempts |1 |1..4 total application attempts; NONE requires1 |
| concurrency |8 | Local1..8, remote1..32 logical operations, including backoff |
| timeoutMs |3500 |100..3500 whole-exchange wait, capped by remaining deadline |
| deadlineMs |10000 |100..10000 from scheduled arrival |
| ttlSeconds |120 |2..120, immutable on retries |
| seed |20261006 | Java long; seeded mixed selection and per-operation jitter |

400: invalid bounds/role/target, malformed fields.409: already active, live prior
HTTP task, local observer unavailable or history/discovery budget exhausted.
202 admission does not mean the target fixture or hold succeeded: poll the run.
States PREPARING, RUNNING, DISCOVERING, COMPLETED, STOPPED, FAILED. A COMPLETED run
may have business conflicts or UNKNOWN outcomes; assess its report.

Report: initialArrivalRate, scheduled/offered, measured attempts, discoveryAttempts,
maxInFlight, virtualThreadAttempts, per-status and per-outcome counts, HTTP and
logical avg/p50/p95/p99/max, per-second and per-kind HTTP summaries. Status0 means
client error/timeout, not a server response. Wall time spans first through final
completion, so100 paced calls at10/s usually finish near9.9s; requested duration
defines the nominal arrival window. HTTP timings include transfer/body parsing;
logical timings include retries/backoff. Setup and discovery are separate traffic.

Discovery can observe200 same-key replay even after expiry. It proves the booking
identity exists; it does not promise the seat is still held. UNKNOWN followed by
409/another error remains UNKNOWN. Repeating discovery returns409. Restarts do
not restore Java in-memory history or reset persisted recovery evidence.
