# Startup and inspection

1. Build Java21 with `mvn -B -ntp -f load-tester-java/pom.xml verify` from the repo root.
2. Check the existing booking gateway and start a fresh metadata observer.
3. Start only the load tester with the selected local or Ankita Compose file.
4. POST a finite run, poll its status, then inspect the report and server metadata.
5. Stop the generator with scoped `docker compose ... stop`; retain all fixtures,
   reports and containers. No `down`, `clean`, `--rm`, resets or pruning needed.

Exact PowerShell commands, ports, IntelliJ reading order, interview points and an
exercise are in the [main tutorial](../../docs/JAVA_LOAD_TESTER_TUTORIAL.md).
Import [collection](../postman/java-load-tester.postman_collection.json) and
[local environment](../postman/local.postman_environment.json); run in order with
1200ms request delay. It creates one event and one hold, performs zero-unknown
discovery, verifies its second admission is rejected and checks bounds. Keep the
observer alive for the entire collection (at least30s).

Troubleshooting:409 OBSERVER_* requires a fresh live control path; finished
observers leave STOP evidence and must not be reset. FAILED after202 usually
means readiness/fixture failure: inspect state/error, do not blindly repeat setup.
STOPPED with GENERATOR_SATURATED means the offered workload exceeded the logical
cap; increase capacity only inside documented bounds and label the next run.
UNKNOWN requires same-key discovery after the original run drains; do not create
a new booking key. Local target failure can be checked without starting a run.
