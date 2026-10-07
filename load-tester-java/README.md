# Java Book My Show load tester

[Project status — 2026-10-07](../docs/PROJECT_STATUS.md): delivered and locally
verified; generator and business services currently stopped/retained. Final
Ankita execution pending; runtime setup requires a fresh observer and target check.

Standalone Java21 / Spring Boot3.5.16 service, adapted from the existing
`D:/java-projects/url-shortener-load-test` Spring Boot generator. Each logical
operation runs on a virtual thread and calls the booking API through blocking
Spring `RestClient`. Its Maven build is independent of the booking backend.

Start with the [tutorial and local/Ankita commands](../docs/JAVA_LOAD_TESTER_TUTORIAL.md).
Read [spec](docs/SYSTEM_SPEC.md), [API](docs/API_REFERENCE.md),
[guide](docs/USER_GUIDE.md), [reference mapping](PARITY.md) and
[verification](docs/VERIFICATION.md).

```powershell
# From D:/sd-book-my-show; no clean command needed.
mvn -B -ntp -f load-tester-java/pom.xml verify
```

Control service: loopback8135. POST `/load/runs` starts one finite run; GET its
status/report; POST `/load/runs/{id}/stop` stops further arrivals/retries.
HOLD, AVAILABILITY (first100 seats) and seeded MIXED use a fresh generic event
per run. Default10 RPS/10s/100 seats/one attempt. Optional bounded IMMEDIATE or
JITTER retries preserve buyer/key/payload. Unknown holds have a separate two-call
discovery budget. HTTP/status metadata is saved; request/response bodies are not.

Local Docker group: `sd-book-my-show-java-load-test`,384 MiB/0.5 CPU, separate
from both business services and the retained Node generator. Local runs require
the existing metadata observer. Final remote experiments belong on Ankita.
Redis remains deferred to phase3; PostgreSQL remains at1 GiB.

Import [Postman collection](postman/java-load-tester.postman_collection.json)
and [local environment](postman/local.postman_environment.json) after starting
the loader. This collection deliberately creates only one measured hold; use
the tutorial's explicit100-call command for the selected10 RPS smoke.
