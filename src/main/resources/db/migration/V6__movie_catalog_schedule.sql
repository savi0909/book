-- Permanent movie catalog metadata plus support for the rolling show window.
-- Catalog rows (V7) are data only; shows/seats are generated and purged by
-- MovieScheduleMaintainer for today..today+window in each multiplex zone.
CREATE TABLE movie_catalog_sites (
  multiplex_id uuid PRIMARY KEY REFERENCES movie_multiplexes(id),
  brand varchar(20) NOT NULL, city varchar(60) NOT NULL, state varchar(60) NOT NULL,
  price_tier integer NOT NULL CHECK (price_tier BETWEEN 1 AND 3)
);
CREATE INDEX movie_catalog_sites_city ON movie_catalog_sites(city);
CREATE TABLE movie_catalog_prices (
  price_tier integer NOT NULL CHECK (price_tier BETWEEN 1 AND 3),
  category varchar(1) NOT NULL CHECK (category IN ('A','B','C')),
  price_minor integer NOT NULL CHECK (price_minor > 0),
  PRIMARY KEY (price_tier, category)
);
CREATE TABLE movie_now_showing (
  movie_id uuid PRIMARY KEY REFERENCES movies(id),
  weight integer NOT NULL CHECK (weight BETWEEN 1 AND 100),
  blockbuster boolean NOT NULL DEFAULT false
);
CREATE TABLE movie_purge_log (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  purged_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  shows integer NOT NULL, seats integer NOT NULL, bookings integer NOT NULL,
  payments integer NOT NULL, refunds_required integer NOT NULL
);
-- Purge deletes parents in bulk; these back the foreign-key checks that V5 left unindexed.
CREATE INDEX movie_bookings_show ON movie_bookings(show_id);
CREATE INDEX movie_booking_seats_show_seat ON movie_booking_seats(show_id, seat_number);
CREATE INDEX movie_booking_audit_booking ON movie_booking_audit(booking_id);
CREATE INDEX movie_shows_starts ON movie_shows(starts_at);
