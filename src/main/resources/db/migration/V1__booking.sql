CREATE TABLE events (
  id uuid PRIMARY KEY,
  name varchar(80) NOT NULL,
  seat_count integer NOT NULL CHECK (seat_count BETWEEN 1 AND 200),
  price_minor integer NOT NULL DEFAULT 5000 CHECK (price_minor > 0),
  currency varchar(3) NOT NULL DEFAULT 'INR',
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE seats (
  event_id uuid NOT NULL REFERENCES events(id),
  seat_number integer NOT NULL CHECK (seat_number BETWEEN 1 AND 200),
  PRIMARY KEY (event_id, seat_number)
);
CREATE TABLE bookings (
  id uuid PRIMARY KEY,
  event_id uuid NOT NULL,
  seat_number integer NOT NULL,
  buyer_id varchar(64) NOT NULL,
  hold_key varchar(128) NOT NULL,
  ttl_seconds integer NOT NULL CHECK (ttl_seconds BETWEEN 2 AND 120),
  state varchar(20) NOT NULL CHECK (state IN ('HELD','CHECKOUT','CONFIRMED','EXPIRED','CANCELLED','PAYMENT_FAILED')),
  reconciliation varchar(24) NOT NULL DEFAULT 'NONE' CHECK (reconciliation IN ('NONE','REFUND_REQUIRED','REFUNDED_SIMULATED')),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY (event_id, seat_number) REFERENCES seats(event_id, seat_number),
  UNIQUE (buyer_id, hold_key)
);
-- Time is deliberately excluded from this index. Expired physical rows are
-- transitioned under the seat lock before a new active booking is inserted.
CREATE UNIQUE INDEX one_active_booking_per_seat ON bookings(event_id, seat_number)
  WHERE state IN ('HELD','CHECKOUT','CONFIRMED');
CREATE INDEX expiring_holds ON bookings(expires_at) WHERE state IN ('HELD','CHECKOUT');
CREATE TABLE payments (
  id uuid PRIMARY KEY,
  booking_id uuid NOT NULL UNIQUE REFERENCES bookings(id),
  checkout_key varchar(128) NOT NULL,
  scenario varchar(16) NOT NULL CHECK (scenario IN ('SUCCESS','FAILURE','UNKNOWN')),
  delay_ms integer NOT NULL CHECK (delay_ms BETWEEN 0 AND 10000),
  state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','UNKNOWN','SUCCESS','FAILURE')),
  ready_at timestamptz NOT NULL,
  next_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  lease_token uuid,
  lease_until timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX recoverable_payments ON payments(next_at) WHERE state IN ('PENDING','UNKNOWN');
-- A durable LOCAL provider simulator. Its independent commit stands in for an
-- external provider accepting a request before the application sees a response.
CREATE TABLE provider_receipts (
  payment_id uuid PRIMARY KEY REFERENCES payments(id),
  outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE')),
  available_at timestamptz NOT NULL,
  accepted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE provider_events (
  event_id varchar(128) PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES payments(id),
  outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE booking_audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  booking_id uuid NOT NULL REFERENCES bookings(id),
  action varchar(40) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
