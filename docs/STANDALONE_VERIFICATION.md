# Standalone extraction verification - 2026-10-05

Subsequent movie expansion and standalone stack startup are recorded in
[movie verification](MOVIE_VERIFICATION.md). The extraction-phase record below
precedes that new work and preserves its original scope.

This document records checks for the independently initialized repository at
`D:/sd-book-my-show`. Original runtime evidence remains in
[VERIFICATION.md](VERIFICATION.md) and `docs/evidence/` with its real dates.

- Imported14 ordered historical ticket-project snapshots from the original Java
  workspace. Compared every tracked path, mode and Git blob ID in each imported
  tree with its source; all14 match exactly except the added provenance file.
- Verified that both author and committer timestamps equal the assigned sprint
  schedule. Dates increase strictly from September15 to October5,21 inclusive
  calendar days. Source SHAs/dates are retained in commit trailers and
  [the mapping](HISTORY_RECONSTRUCTION.json).
- Original source files, workspace main history, IntelliJ edits and live services
  were not modified. Application/database/provider data was not copied.
- Standalone setup changes Maven artifact/Docker jar naming, Spring application
  name, Compose project and host addresses. Container ports, booking transitions,
  SQL migrations and Java correctness logic are preserved.

Executed `mvn -B -ntp verify`:53 tests,0 failures/errors/skips, real PostgreSQL
Testcontainers plus controlled provider HTTP; executable
`target/sd-book-my-show-1.0.0.jar` built. Java21.0.9, Boot3.5.16;
Mockito emitted an existing dynamic-agent warning without failed tests.

Executed artifact validator:56 required files,139 named requests,146 parsed
script blocks,268 local links,0 broken links.34 absolute historical reference
links are counted separately; the original workspaces are not build dependencies.
All JavaScript files passed `node --check`. All five Compose combinations passed:
base; base+failover; base+failover+provider; base+failover+poison;
base+failover+outbox. Configuration validation does not mean services were started.

No copied application stack or runtime failure harness has been started.
Origin publication awaits the new repository URL; local initialization does not
mean the repository has been pushed.
