# Standalone SD Book My Show setup

Created on October 5, 2026 at `D:/sd-book-my-show` with a fresh `git init`.
The source lab remains in `D:/java-projects/ticket-booking-lab`.

## What is included

This is one independent Maven application, with Java 21, Spring Boot 3.5.16,
JDBC, Flyway and PostgreSQL. The root workspace POM was an IDE aggregator, not
an inherited parent. There are **no dependent workspace modules to copy**.

The repository contains all tracked Java production/test code, migrations V1-V4,
the independent Java provider stub under `infra/`, its Dockerfile, HAProxy config,
base Compose and four study overlays, runtime scripts, Postman collections and
environments, tutorials and the original committed verification evidence.
Dependencies in `pom.xml` are resolved through Maven. PostgreSQL and HAProxy
are container images; their source repositories are not application modules.

The original `.git`, `.idea`, build outputs and live database/provider volumes
were not copied. Running this stack creates separate retained data; it does not
transfer the original lab's event IDs or booking history.

## Open and build

Open `D:/sd-book-my-show/pom.xml` in IntelliJ as a Maven project, select Java 21
and allow Maven to resolve dependencies. There is no Maven wrapper.

```powershell
Set-Location D:/sd-book-my-show
java -version
mvn -version
mvn -B -ntp verify
node scripts/validate-artifacts.mjs
```

Integration tests use real PostgreSQL through Testcontainers and need Docker.
They do not need the original application running. Do not run `mvn clean`.

## Separate local addresses

| Service | Original lab | This standalone repository |
| --- | --- | --- |
| API A | localhost:8105 | localhost:8130 |
| API B | localhost:8106 | localhost:8131 |
| Failover gateway | localhost:8107 | localhost:8132 |
| Optional provider | localhost:8123 | localhost:8133 |
| PostgreSQL | localhost:5547 | localhost:5553 |
| Compose project | ticket-booking-java | sd-book-my-show |

The Compose application containers still listen on port8105; HAProxy uses those
internal service addresses. The provider still listens on8121 inside its container.
Only host ports changed. Default host JDBC uses5553. For a host-only API, set
`$env:PORT="8130"` before `mvn spring-boot:run`; the inherited default is8105.

Container volume names are scoped to `sd-book-my-show`; the original lab's
volumes are retained. No application stack was started during extraction.
Check port ownership on your machine before starting services.

## Start when you want to exercise the copy

```powershell
Set-Location D:/sd-book-my-show
docker compose -f compose.yml -f compose.failover.yml config --quiet
docker compose -f compose.yml -f compose.failover.yml build api-a api-b
docker compose -f compose.yml -f compose.failover.yml up -d --wait
node scripts/learn-booking.mjs
npx --yes newman@6.2.2 run postman/ticket-booking-lab.postman_collection.json -e postman/local.postman_environment.json
```

Run the failure exercises separately and read their tutorials first. The copied
scripts now address8130-8133 and use this directory's Compose project. They can
restart this project's services. Original tutorials retain historical addresses:
use the mapping above and the updated Postman environments for this repository.

For the independent provider, add `-f compose.provider.yml` after the base and
failover files. For poison or outbox manual studies, use their own overlay with
the default simulator, as explained in the relevant tutorials. Do not combine
all fault overlays at once. Stop without deleting data using `docker compose`
with the same selected files followed by `stop`.

## Fresh origin

Create an **empty** GitHub repository named `sd-book-my-show`, without a generated
README, license or gitignore. Its main branch will contain this standalone project
at the root. The original Java workspace remote is not this repository's origin.

After its URL is supplied, the publishing commands are:

```powershell
Set-Location D:/sd-book-my-show
git remote add origin https://github.com/YOUR_ACCOUNT/sd-book-my-show.git
git push --set-upstream origin main
git ls-remote origin refs/heads/main
```

Do not use force-push. The URL above is a placeholder, not a configured remote.
The last command must return the same SHA as `git rev-parse HEAD` before claiming
publication succeeded. See [the sprint plan](THREE_WEEK_SPRINT_PLAN.md) and
[provenance](../TIMELINE_PROVENANCE.md) for the reconstructed history.
