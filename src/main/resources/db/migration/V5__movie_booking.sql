CREATE TABLE movie_multiplexes (
  id uuid PRIMARY KEY, name varchar(100) NOT NULL, zone_id varchar(80) NOT NULL,
  screen_count integer NOT NULL CHECK (screen_count BETWEEN 5 AND 100)
);
CREATE TABLE movie_screens (
  id uuid PRIMARY KEY, multiplex_id uuid NOT NULL REFERENCES movie_multiplexes(id),
  name varchar(80) NOT NULL, seat_count integer NOT NULL CHECK (seat_count BETWEEN 200 AND 500),
  UNIQUE (multiplex_id, name)
);
CREATE TABLE movie_screen_categories (
  screen_id uuid NOT NULL REFERENCES movie_screens(id), category varchar(1) NOT NULL CHECK (category IN ('A','B','C')),
  percentage integer NOT NULL CHECK (percentage BETWEEN 1 AND 99), seat_count integer NOT NULL CHECK (seat_count > 0),
  PRIMARY KEY (screen_id, category)
);
CREATE TABLE movies (
  id uuid PRIMARY KEY, title varchar(100) NOT NULL, language varchar(40) NOT NULL,
  duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 360)
);
CREATE TABLE movie_shows (
  id uuid PRIMARY KEY, movie_id uuid NOT NULL REFERENCES movies(id), screen_id uuid NOT NULL REFERENCES movie_screens(id),
  starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, occupied_until timestamptz NOT NULL,
  CHECK (ends_at > starts_at AND occupied_until >= ends_at), UNIQUE (screen_id, starts_at)
);
CREATE INDEX movie_shows_schedule ON movie_shows(screen_id, starts_at);
CREATE TABLE movie_bookings (
  id uuid PRIMARY KEY, show_id uuid NOT NULL REFERENCES movie_shows(id), buyer_id varchar(64) NOT NULL,
  hold_key varchar(128) NOT NULL, ttl_seconds integer NOT NULL CHECK (ttl_seconds BETWEEN 2 AND 900),
  state varchar(24) NOT NULL CHECK (state IN ('HELD','PAYMENT_PENDING','CONFIRMED','EXPIRED','CANCELLED')),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0), currency varchar(3) NOT NULL DEFAULT 'INR',
  expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  current_payment_id uuid, UNIQUE (buyer_id, hold_key), UNIQUE (id, show_id)
);
CREATE INDEX expiring_movie_bookings ON movie_bookings(expires_at) WHERE state IN ('HELD','PAYMENT_PENDING');
CREATE TABLE movie_show_seats (
  show_id uuid NOT NULL REFERENCES movie_shows(id), seat_number integer NOT NULL CHECK (seat_number BETWEEN 1 AND 500),
  category varchar(1) NOT NULL CHECK (category IN ('A','B','C')), price_minor integer NOT NULL CHECK (price_minor > 0),
  active_booking_id uuid, PRIMARY KEY (show_id, seat_number),
  FOREIGN KEY (active_booking_id, show_id) REFERENCES movie_bookings(id, show_id)
);
CREATE INDEX movie_seat_owner ON movie_show_seats(active_booking_id) WHERE active_booking_id IS NOT NULL;
CREATE TABLE movie_booking_seats (
  booking_id uuid NOT NULL, show_id uuid NOT NULL, seat_number integer NOT NULL,
  category varchar(1) NOT NULL, price_minor integer NOT NULL CHECK (price_minor > 0),
  PRIMARY KEY (booking_id, seat_number),
  FOREIGN KEY (booking_id, show_id) REFERENCES movie_bookings(id, show_id),
  FOREIGN KEY (show_id, seat_number) REFERENCES movie_show_seats(show_id, seat_number)
);
CREATE TABLE movie_payments (
  id uuid PRIMARY KEY, booking_id uuid NOT NULL REFERENCES movie_bookings(id), checkout_key varchar(128) NOT NULL,
  payment_number integer NOT NULL CHECK (payment_number > 0),
  state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','RETRY_PENDING','SUCCEEDED','FAILED')),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  plan_bucket integer NOT NULL CHECK (plan_bucket BETWEEN 0 AND 9999), requested_test_bucket integer,
  delay_ms integer NOT NULL CHECK (delay_ms BETWEEN 0 AND 10000),
  provider_step integer NOT NULL DEFAULT 1 CHECK (provider_step BETWEEN 1 AND 2),
  dispatches integer NOT NULL DEFAULT 0, next_at timestamptz NOT NULL,
  lease_token uuid, lease_until timestamptz, refund_required boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (booking_id, checkout_key), UNIQUE (booking_id, payment_number), UNIQUE (id, booking_id)
);
CREATE UNIQUE INDEX one_open_movie_payment ON movie_payments(booking_id) WHERE state IN ('PENDING','RETRY_PENDING');
ALTER TABLE movie_bookings ADD FOREIGN KEY (current_payment_id, id) REFERENCES movie_payments(id, booking_id);
CREATE INDEX due_movie_payments ON movie_payments(next_at) WHERE state IN ('PENDING','RETRY_PENDING');
CREATE TABLE movie_provider_receipts (
  payment_id uuid NOT NULL REFERENCES movie_payments(id), provider_step integer NOT NULL CHECK (provider_step BETWEEN 1 AND 2),
  outcome varchar(24) NOT NULL CHECK (outcome IN ('SUCCESS','RETRYABLE_FAILURE','FAILURE')),
  accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (payment_id, provider_step)
);
CREATE TABLE movie_booking_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, booking_id uuid NOT NULL REFERENCES movie_bookings(id),
  action varchar(40) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
