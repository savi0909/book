# Reference mapping

Reference inspected2026-10-06: existing Java module
`D:/java-projects/url-shortener-load-test`, especially LoadRunService,
ClientConfig, LoadController, application.yml and LoadBalancingTest.
This adapts its generator design; it does not claim URL-shortener API parity.

| Reference | Booking adaptation |
| --- | --- |
| Independent Boot3.5.16/Java21 POM | Same baseline; standalone subfolder build |
| Virtual-thread executor + blocking RestClient | Same model; whole-exchange timeout and task-closure guard |
| Fixed schedule independent of response latency | Fixed initial arrivals; bounded logical concurrency; stop on saturation/lag |
| POST/GET/MIXED links | HOLD/AVAILABILITY/MIXED generic tickets |
| Spring Cloud round-robin client | One configured HAProxy gateway already balances booking A/B; no extra balancer |
| Owner/requestKey in JSON | Buyer in hold JSON; Idempotency-Key header |
| Stored link codes and response samples | Fresh event per run; metadata-only IDs/keys/reports, no body samples |
| No retry policy | Default NONE; explicit IMMEDIATE/JITTER, at most4 total attempts and10s deadline |
| In-memory run control | Start/status/list plus stop and separate capped unknown-hold discovery |
| Large configurable rate/duration | Finite learning bounds: local100, remote200 operations/run |

Older Node controlled faults/two-arm orchestration/crash recovery are preserved
in their module; this Java service does not automatically import those manifests
or drive the scoped Node ingress. Java tests simulate busy/response-loss cases.
Java runtime smoke uses the real generic booking API without faults. Movie V5
group holds/payment/SSE and high-throughput/100K scenarios are separate work.
