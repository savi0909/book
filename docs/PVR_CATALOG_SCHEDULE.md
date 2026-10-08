# Permanent PVR catalog and rolling show window

Delivered 2026-10-07 on branch `feature/pvr-rolling-catalog`. This sets up the
movie domain's catalog data for the later booking/cancellation simulation. It
does not generate any booking or cancellation traffic itself.

**Study guide (2026-10-08):** [movie advance-booking tutorial](MOVIE_ADVANCE_BOOKING_TUTORIAL.md),
parts 1–5: catalog, fill, purge, booking window and the JIT CPU case study.
Since this was written, every batch also runs `SET LOCAL jit = off` (see part 5).
Generator caveat: `scripts/generate_pvr_catalog.py` currently writes straight to V7.
Redirect its output into a new migration (reported 2026-10-08, not fixed).

## What exists now

| Piece | Where | Behaviour |
| --- | --- | --- |
| Property list | [docs/data/pvr_properties.csv](data/pvr_properties.csv) | 303 real PVR / INOX property names in 77 cities, each with its source URL (mostly district.in city pages, researched 2026-10-07) |
| Generator | [scripts/generate_pvr_catalog.py](../scripts/generate_pvr_catalog.py) | Deterministic (uuid5 IDs, RNG seeded by name+city); rerunning it gives byte-identical SQL |
| Schema | `V6__movie_catalog_schedule.sql` | `movie_catalog_sites` (brand/city/state/price tier), `movie_catalog_prices`, `movie_now_showing`, `movie_purge_log`, plus four indexes for purge FK checks |
| Permanent data | `V7__pvr_catalog_data.sql` | 303 multiplexes, 1,971 screens, 696,940 seats; 20-film synthetic slate (3 blockbusters, 40% of the pick weight); 3 tiers × 3 class prices |
| Rolling window | `MovieScheduleMaintainer` | On startup and every 15 minutes: purge past local days, then fill today..today+3 |
| Booking rule | `MovieBookingService.hold` | `409 SHOW_NOT_YET_OPEN` when the show's local date is after today+3 |

**Real vs synthetic.** Only the property names, cities and states are real. The
sources did not state screen counts, so every layout is generated:
- Screens: tier 1 gets 5–9, tier 2 gets 5–7, tier 3 gets 5–6. This totals about
  1,970, close to PVR INOX's reported ~1,800 screens.
- Seats: 200–500 per screen, in steps of 10.
- Classes: 2 classes 45% of the time (A 15–30%), otherwise 3 classes (A 8–15%,
  B 25–35%).
- Prices and movies are synthetic.

The source list is incomplete. district.in pages list only cinemas near city
centres, against the chain's ~358 properties.

## Schedule rules

- Each screen-day plays **one** now-showing movie. It is picked by weight from a
  stable hash of the screen ID and the date, so a refill reproduces the same plan.
- The first show starts between 09:00 and 10:30 local time, staggered by screen.
  Slot length is runtime + 20 minutes of turnaround, rounded up to 5 minutes. The
  last start is at or before 23:30. That gives 5–8 shows per screen per day.
- Show seats are numbered A, then B, then C, the same as `MovieCatalog.createShow`.
  Prices are frozen per seat from the multiplex's tier:

  | Tier | A | B | C |
  | --- | --- | --- | --- |
  | 1 | ₹550 | ₹350 | ₹220 |
  | 2 | ₹420 | ₹280 | ₹180 |
  | 3 | ₹320 | ₹220 | ₹150 |

- Today is filled with future slots only. A screen-day that already has any show is
  skipped, so ticks are idempotent and a fill interrupted mid-way resumes cleanly.

## Purge rules

The purge deletes every show whose start is before today's local midnight, and
everything that depends on it:
- receipts
- payments
- audit
- booking seats
- show seats
- bookings
- the show

This applies to **all** movie shows, including demo/test shows created through the
API. Each batch of 60 shows is one SQL statement. NO ACTION foreign keys are checked
at the end of the statement, which also covers the `movie_bookings` ↔
`movie_payments` reference cycle.

- **Kept:** shows with a `PENDING`/`RETRY_PENDING` payment, until the payment worker
  settles them.
- **Deleted but counted:** `refund_required` payments. They are deleted (the user
  chose whole-day deletion), but each batch records how many it removed in
  `movie_purge_log.refunds_required`. Nothing else retains that liability once it
  is purged.

## Operation

- `MOVIE_SCHEDULE_ENABLED`:
  - Base `compose.yml` sets it to `true` on both APIs.
  - The application default and the tests use `false`.
  - `compose.movie.yml` (manual payment-study overlay) turns it off.
- Each batch takes `pg_try_advisory_xact_lock`. The replica that loses the lock
  skips the rest of that tick. Because of the unique key and the per-screen-day
  check, a race would also be harmless.
- The maintainer runs on its own daemon thread. A long first fill therefore never
  delays the 200 ms payment loop or the 500 ms expiry loop, which share Spring's
  single scheduler thread. Batches are 3 multiplexes × 1 day, which stays within
  `BookingStore.tx`'s 3 s statement timeout.
- `MOVIE_BOOKING_WINDOW_DAYS` (default 3) controls both the fill horizon and the
  hold rule. `MOVIE_SCHEDULE_INTERVAL_MS` defaults to 900000.

Useful queries:

```sql
SELECT cs.city, count(*) FROM movie_catalog_sites cs GROUP BY 1 ORDER BY 2 DESC;
SELECT (starts_at AT TIME ZONE 'Asia/Kolkata')::date AS day, count(*) FROM movie_shows GROUP BY 1 ORDER BY 1;
SELECT * FROM movie_purge_log ORDER BY id DESC LIMIT 10;
```

## Verification

Results executed on 2026-10-07 are recorded in [project status](PROJECT_STATUS.md):
`mvn -B -ntp verify` passed 74 tests, including 5 new `MovieScheduleIntegrationTest`
cases:
- catalog rules
- idempotent fill with no overlaps and correct prices
- the today+3 boundary
- a full-chain purge that keeps open payments
- a lock-loser skip

## Interview points

1. **Pre-materialized seat inventory makes the cost explicit.** Every show writes
   one row per seat, about 4M rows per day for this catalog. The rolling purge keeps
   the database close to a steady 4-day size instead of growing without bound.
2. **Set-based generation over row-by-row inserts.** A single `INSERT … SELECT
   generate_series` with data-modifying CTEs creates shows and seats together. The
   batch size is chosen to fit the existing statement timeout, not the other way
   round.
3. **Idempotent schedulers plus advisory locks.** The unique `(screen_id, starts_at)`
   key and the screen-day existence check make retries safe. The advisory lock only
   prevents duplicated work between replicas.
4. **Deleting a graph needs index support on child foreign keys.** Without indexes on
   `movie_booking_seats(show_id, seat_number)`, `movie_bookings(show_id)` and the
   audit `booking_id`, every parent delete would sequentially scan its children.
5. **Data retention is a domain decision.** Purging open payments would race the
   worker, so they are kept. Refund obligations are counted before deletion so the
   loss is visible.
