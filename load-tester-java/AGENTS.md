# Java booking load generator

Follow [parent instructions](../AGENTS.md). Read [README](README.md),
[spec](docs/SYSTEM_SPEC.md), [API](docs/API_REFERENCE.md), [guide](docs/USER_GUIDE.md),
[verification](docs/VERIFICATION.md), [parity](PARITY.md) and
[tutorial](../docs/JAVA_LOAD_TESTER_TUTORIAL.md) before changes.

Standalone Java21/Boot3.5.16; build with `mvn -B -ntp verify` here, no clean.
No backend Maven parent/module change. Virtual threads plus bounded logical
concurrency; no unlimited queue. Keep one HTTP task per logical operation at a
time. A timeout may hide commit; same-key retry/discovery only.409 is terminal.
Separate application attempts from initial arrivals and discovery traffic.
No request/response-body storage. Preserve keys, fixtures, reports and markers.

Actual services remain in sd-book-my-show; generator gets its own Docker group.
Ankita is the final business-load origin. Explicitly selected local exception
is at most10 RPS/10s/100 operations, with a live metadata observer. Remote
mode remains finite (200 operations/run) and requires an Ankita hostname and
literal Tailscale target; this is a configuration guard, not host authentication.
Do not expose management ports remotely or silently configure Tailscale.
Redis phase3 only; PostgreSQL authoritative. Do not add movie/payments/SSE,
shared admission or massive load solely because future docs mention them.

The older Node module is retained. Never delete files/directories/data/containers
or remove markers without explicit permission. Preserve unrelated stacks.
After task validation commit; push only to this repo's configured origin and
verify it. Origin is currently absent. Update canonical learning records.
