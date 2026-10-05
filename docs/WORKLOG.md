# SD Book My Show worklog

Actual delivery record. Reconstructed sprint dates are described in
[provenance](../TIMELINE_PROVENANCE.md), not treated as actual work dates here.
Current resume entry: [project context](../PROJECT_CONTEXT.md).

## 2026-10-05: independent repository and movie backend

Created `D:/sd-book-my-show` with fresh Git init, as requested. Imported14 actual
ticket-lab snapshots onto a disclosed September15-October5 study schedule;
verified their tracked content against source snapshots. Included production/test
code, migrations, provider stub, Compose/configs, scripts, Postman and tutorials.
No dependent workspace modules were required. Original repository/data preserved.

Standalone setup commit `1be6f82` changed artifact/Compose naming and host ports
to avoid the original lab, and added setup, sprint mapping and verification docs.
Initial extraction build passed53 tests. No new origin was supplied/configured.

User then selected a movie backend and explicitly chose atomic multiple-seat
reservations and retaining seats after payment failure until original expiry.
Movie implementation commit `6c2e57a` added V5,5-100 screens,200-500 seats,
configurable categories, dated shows, frozen prices, atomic1-10-seat groups,
durable mock retries and fresh user payment attempts with retained history.

Delivered [movie tutorial](MOVIE_BOOKING_TUTORIAL.md), [API](MOVIE_API_REFERENCE.md),
[ADR](ADR_001_MOVIE_GROUP_BOOKING.md), [verification](MOVIE_VERIFICATION.md),
Postman/manual overlay/runtime scripts and [future-work notes](FUTURE_WORK.md).
The [thousands-user simulation](MOVIE_LOAD_SIMULATION_PLAN.md) remains a saved plan.

Final validation:68 tests;23 A/B runtime checks; movie Newman30requests/60assertions;
generic Newman75requests/110assertions;6 scheduled-mode checks. No failed final
checks.32 competing group requests yielded1 complete winner and31 conflicts.
SQL found no pending movie payments or confirmed member ownership mismatches.
These results establish bounded correctness, not sustained capacity or production HA.

Standalone base+failover was left running with automatic workers and controls off,
all data retained. Original ticket and shortener services were not operated on.
Shared learning context/progress/decisions and the canonical ticket case were updated.

## 2026-10-05: local context and agent handoff

User requested a worklog/project-context update and AGENTS.md if missing.
Confirmed AGENTS.md already exists. Added [local project context](../PROJECT_CONTEXT.md)
and this worklog; linked them from AGENTS.md, CLAUDE.md, README and future handoff.
Current movie rules take precedence over inherited generic study notes.

Validation for this documentation update: project artifact/link validator and Git
whitespace checks; application tests were not rerun because executable behavior
is unchanged. Local documentation changes are committed with actual timestamps.
Push remains pending the fresh origin URL; no publication is claimed.
