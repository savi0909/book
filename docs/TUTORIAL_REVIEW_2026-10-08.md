# Tutorial review — 2026-10-08

Review of the eleven tutorials named in the [handover](HANDOVER_TUTORIAL_REVIEW.md),
checked against the code, Compose files, migrations, run reports and read-only
`docker ps` / `docker inspect` on 2026-10-08. Nothing was executed: no tests, no
load, no container start, stop or recreate, and no database queries.

Method:
- Grep for the known stale claims.
- A regex scan for numbers glued to words.
- A link and anchor checker over all Markdown.
- An identifier check: every `Class.method` named in the tutorials exists in the code.
- Structure counts (exercises, interview points, diagrams).
- Manual reading against the source.

Severity: **wrong** (contradicts code or state), **stale** (true when written, now
outdated), **unclear**, **gap** (missing teaching element).

| # | File:line (at review start) | Issue | Evidence | Resolution | Severity |
|---|---|---|---|---|---|
| 1 | PROJECT_STATUS.md:86-91 | Said the stack was running, and "1 GiB / 2 GiB limits retained" right after saying 3 GB | `docker inspect`: postgres 3,221,225,472 B / 2 CPUs; all containers Exited | Fixed in 3e1bb39: new dated runtime table and a correction note | wrong |
| 2 | PROJECT_STATUS.md:15,34 | Named the JIT fix as `46ff584`, which is on no branch | `git branch -a --contains 46ff584` is empty; the fix is in `834c23b` | Fixed in 3e1bb39 | wrong |
| 3 | HANDOVER_TUTORIAL_REVIEW.md | Said the maintainer runs on "api-a only" | `compose.yml` `&api-env` is merged into api-b; inspect shows it on both | Fixed in 3e1bb39 with a dated correction | wrong |
| 4 | PROJECT_STATUS.md:19, AGENTS.md:12, loader README | "Loader 1 CPU" read as already applied | The retained loader container has NanoCpus 0.5e9 and `-Xmx192m`; it was created at 09:44Z on 10-07, and smoke 2 plus the 537-journey run used it | Fixed in 3e1bb39 with a note ("committed default; applies on recreate"); taught in tutorial part 7 | unclear |
| 5 | PROJECT_STATUS.md:5,21-25,130-136 | Old branch name; "loader is generic-only"; "no origin" | `834c23b` on main, `MovieRunService`, `git remote -v` | Fixed in 3e1bb39 | stale |
| 6 | JAVA_LOAD_TESTER_TUTORIAL.md:7-8,113-115,120,159-165 | Movie load "separate work"; 0.5 CPU; PostgreSQL 1 GiB; "origin absent"; reading order lacked movie classes | Code and Compose | Fixed in 3e1bb39 | stale |
| 7 | DOCKER_LOAD_TEST_TUTORIAL.md:14-18,34 | Table header "Current limit" showed 1 GiB; "shared_buffers unchanged" | `compose.yml` | Fixed in 3e1bb39: header now "Limit on 2026-10-06" plus a current note | stale |
| 8 | HOLD_LOAD_TEST_TUTORIAL.md:7-8,324-337 | Movie load "future"; bundle checkout because there was no origin | as above | Fixed in 3e1bb39 | stale |
| 9 | MOVIE_BOOKING_TUTORIAL.md:76-99,150,161 | Hold trace lacked `SHOW_NOT_YET_OPEN`; `up` now triggers the catalog fill; overlay also disables the maintainer | `MovieBookingService.hold`; `compose.yml`; `compose.movie.yml` | Fixed in 3e1bb39 | stale |
| 10 | MOVIE_API_REFERENCE.md:8-25 | Missing the two browse routes | `MovieController` lines 23-24 | Fixed in 3e1bb39 | stale |
| 11 | IntelliJ, failover, provider, poison, outbox tutorials | Original-lab ports and paths | `STANDALONE_SETUP.md` address table | Fixed in 3e1bb39: dated port-mapping note in each; history kept | stale |
| 12 | AGENTS, CLAUDE, PROJECT_CONTEXT, README, FUTURE_WORK, STANDALONE_SETUP, loader README/AGENTS | "origin absent", "14 Java tests", "1 GiB", "0.5 CPU", "movie load unbuilt" | 19 loader tests (14 + 5), origin, Compose | Fixed in 3e1bb39 and the records commit: dated current-state lines; dated sections kept | stale |
| 13 | ~250 places in 20 files | Numbers glued to words ("stays1 GiB"), digit-to-digit glue ("100/100201", "p508.82ms") | regex scan | Fixed in 33c8849; proven whitespace-only by script | unclear |
| 14 | API failover; IntelliJ; Docker; foundations; all tutorials | No exercises; no interview points; no interview points; no mermaid diagram (ASCII only); no misconception boxes | structure scan | Fixed in 6e406ab | gap |
| 15 | INTERVIEW_GUIDE.md | No advance-booking or load-generation track | headings | Fixed in afb0d1c | gap |
| 16 | 5 tutorials (17 links) | Absolute `D:/AA-SYSTEM-DESIGN-ARCHITECTURE/...` links | They resolve on Abhishek's machine, not on a fresh clone | **Deferred:** deliberate links to local learning sources; noted in the study path | stale |
| 17 | scripts/generate_pvr_catalog.py:18 | **Code defect:** `TARGET` is hard-coded to V7, so a rerun after a CSV edit rewrites an applied migration and Flyway validation fails | Contradicts the AGENTS rule "regenerate into a NEW migration" | **Not fixed**, because code changes need the user's approval. Documented in PROJECT_STATUS pending item 7, the PVR doc and tutorial part 1 | wrong (code) |
| 18 | ANKITA_POSTGRES.md "Things to expect" | "Expect higher latency rather than new failures" ignores that the 5 s Spring transaction timeout includes network time | Hold ≈ 14 + 2n round trips (counted from code); 10 seats × 150 ms DERP ≈ 5.1 s | Fixed in afb0d1c: dated refinement; taught in tutorial part 8 | unclear |

Risk noted but not a defect: every fill batch must finish within the 3 s
`statement_timeout`. A batch that keeps exceeding it on slower hardware would
stall the window at that batch on every tick (tutorial part 2).

Re-checks after the edits: the link and anchor checker reports 0 problems across
the root, `docs`, `infra` and `load-tester-java` Markdown files.
