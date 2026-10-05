# Three-week ticketing development and study plan

The schedule spans **September 15-October 5, 2026**, three seven-day sprints.
This is a retrospective planning model built from the already implemented lab.
Assigned Git author and committer dates describe that model, not actual past
work dates. Source development commits are from October2-5. Verification dates
remain unchanged. Read [provenance](../TIMELINE_PROVENANCE.md) and the exact
[source-to-reconstructed commit mapping](HISTORY_RECONSTRUCTION.json).

## Sprint overview

| Sprint | Dates | Goal | Review demonstration |
| --- | --- | --- | --- |
| 1 | Sep15-21 | Correct seat ownership and recoverable checkout | Two buyers race for one seat; only one wins, then a same-key retry discovers the original outcome |
| 2 | Sep22-28 | Keep failure and uncertainty bounded | Kill/drain one API, then isolate a slow or response-losing provider without holding inventory locks |
| 3 | Sep29-Oct5 | Recover bad work and deliver committed state safely | Quarantine a poison payment; redrive deliberately; recover an outbox delivery after effect commit and before ack |

## Sprint 1: booking correctness and foundations

Suggested implementation work, if building from scratch:

1. Sep15: define the one-numbered-seat booking contract, no-oversell invariant,
   state diagrams, API shapes and explicit local-demo limits.
2. Sep16: scaffold the standalone Maven application, datasource, Flyway V1,
   event/seat/booking/payment/audit tables and active-seat unique index.
3. Sep17: implement event browse/create and temporary holds. Lock seat before
   booking; check expiry against the PostgreSQL clock after acquiring locks.
4. Sep18: add durable hold and checkout keys, original-request discovery,
   payment leases and separately committed simulator receipts.
5. Sep19: implement cancellation, expiry and late-success refund reconciliation.
   Distinguish unknown payment outcome from failure.
6. Sep20: exercise competing requests, duplicate callbacks, seat reassignment
   and recovery in PostgreSQL tests; prepare Postman assertions.
7. Sep21: review invariants, capacity assumptions and transaction boundaries;
   demonstrate the baseline and record observations.

Read [system spec](SYSTEM_SPEC.md), [foundations](DISTRIBUTED_SYSTEMS_FOUNDATIONS.md),
`BookingService`, `BookingStore`, `PaymentProcessor`, `V1__booking.sql` and
`BookingIntegrationTest`. Use [the API reference](API_REFERENCE.md) to trace one
hold through checkout and confirmation.

**Exit criteria:** the database remains the inventory authority; one active
owner per seat; same-key requests do not create another logical booking/payment;
late success never steals a reassigned seat. Tests use PostgreSQL rather than an
in-memory approximation. Documentation separates measured results from sizing assumptions.

Actual reconstructed snapshots: Sep15 imported the complete existing baseline;
Sep17 adds the study guide; Sep19 adds foundations. The source baseline was a
single commit. This extraction does **not** invent source commits for each of
the suggested daily implementation steps.

## Sprint 2: API and provider failure containment

1. Sep22: specify failure injections and uncertainty recovery. Decide which
   dependency failures affect readiness and which business paths stay available.
2. Sep23: deliver optional HAProxy, admission/drain lifecycle handling and
   graceful shutdown. A response lost after commit must be recoverable by key.
3. Sep24: review crash versus graceful restart, lease expiry and provider
   boundaries; prepare the provider-isolation handover.
4. Sep25: add an independent stub journal, HTTP timeouts, two client slots per
   API, two actual processing slots and bounded breaker probes.
5. Sep26: persist automatic dispatch budgets and retry timing in V2; add shared
   count/age admission while preserving discovery of existing checkout keys.
6. Sep27: exercise slowness, lost responses, breaker recovery and exhausted
   UNKNOWN work; confirm that late success preserves refund obligations.
7. Sep28: review failure-domain and rollout limits; demonstrate and record evidence.

Read [API failover](API_FAILOVER_TUTORIAL.md),
[provider isolation](PROVIDER_ISOLATION_TUTORIAL.md), `AdmissionGate`,
`ProviderBoundary`, `infra/ProviderStub.java` and `V2__provider_retry_budget.sql`.

**Exit criteria:** no inventory transaction spans provider HTTP; restart/response
loss retains durable request identity; provider/DB errors are classified separately;
retry exhaustion does not manufacture payment failure. A single host, proxy and
database still do not provide infrastructure HA.

Actual reconstructed snapshots: Sep22 failure plan, Sep23 failover, Sep24 handover,
Sep26 provider-isolation delivery. Intermediate suggested days are planning slots.

## Sprint 3: poison jobs, outbox and review kit

1. Sep29: deliver V3 durable caught-item failure history and quarantine after
   three failures; unrelated healthy work must continue.
2. Sep30: review original-ID keyed redrive, token/time fencing and the maximum
   two lifetime admissions. Dependency failures must not become poison failures.
3. Oct1: deliver V4 transactional confirmation/cancellation snapshots, short
   outbox claims, a separate atomic inbox/receipt/projection transaction and
   token/time-checked acknowledgment. Reordering uses monotonic snapshot versions.
4. Oct2: walk through event creation and its Postman request. Review crashes
   before dispatch and after effect commit but before acknowledgment.
5. Oct3: study event pagination and mapping; review stale confirmation after
   cancellation and concurrent duplicate consumption.
6. Oct4: explain transaction callbacks/lambda execution and operational ownership;
   rehearse senior/staff/principal tradeoffs from the interview guide.
7. Oct5: study single-event lookup; finalize standalone packaging, sprint mapping,
   local verification and the new repository handoff.

Read [poison isolation](POISON_JOB_TUTORIAL.md),
[outbox tutorial](TRANSACTIONAL_OUTBOX_TUTORIAL.md), `RecoveryIsolation`,
`OutboxDispatcher`, `LocalNotificationSink`, migrations V3/V4, and the focused
API01-03 walkthroughs linked from the README.

**Exit criteria:** quarantine persists, keyed replay does not dispatch again,
redrive does not reset budgets, source transition/outbox are atomic, duplicate
consumption does not repeat the local receipt, and an older confirmation cannot
override an observed cancellation. Existing history remains unbackfilled.
No real payment/email/broker or external exactly-once guarantee is claimed.

Actual reconstructed snapshots: Sep29 poison isolation; Oct1 outbox; Oct2 event
creation/collection; Oct3 list events; Oct4 lambda walkthrough; Oct5 get event.
Standalone setup is a subsequent commit with its actual creation timestamp.

## Review and verification discipline

For a new implementation, allow each sprint time for coding, meaningful failure
checks, documentation, a demonstration and explain-back. Keep daily tickets small,
but commit independently reviewable changes rather than padding the graph with
empty commits. The inherited [verification](VERIFICATION.md) records actual source
checks; [standalone verification](STANDALONE_VERIFICATION.md) records this extraction.

Exercise: explain why a successful provider receipt can coexist with an expired
booking, why a retry key solves a different problem from a seat lock, and why an
outbox consumer can run twice while its local receipt appears only once.

Future optimistic seat locking, general retry-storm harnesses and scenarios5-6
remain proposals. This extraction does not implement them or advance the API
lessons beyond the current API3 study.
