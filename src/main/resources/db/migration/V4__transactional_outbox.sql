-- Snapshot-event sequence, not optimistic seat locking. Existing bookings start at0.
ALTER TABLE bookings ADD COLUMN delivery_version bigint NOT NULL DEFAULT 0 CHECK (delivery_version>=0);
CREATE TABLE booking_outbox (
  id uuid PRIMARY KEY,
  booking_id uuid NOT NULL REFERENCES bookings(id),
  booking_version bigint NOT NULL CHECK (booking_version>0),
  kind varchar(16) NOT NULL CHECK (kind IN ('CONFIRMED','CANCELLED')),
  schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version=1),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  next_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
  lease_token uuid,
  lease_until timestamptz,
  delivered_at timestamptz,
  last_error varchar(64),
  demo_delay_ms integer NOT NULL DEFAULT 0 CHECK (demo_delay_ms BETWEEN 0 AND 10000),
  UNIQUE (booking_id,booking_version)
);
CREATE INDEX outbox_due ON booking_outbox(next_at,id) WHERE delivered_at IS NULL;
CREATE INDEX outbox_oldest ON booking_outbox(created_at) WHERE delivered_at IS NULL;
CREATE TABLE booking_delivery_projection (
  booking_id uuid PRIMARY KEY REFERENCES bookings(id),
  booking_version bigint NOT NULL DEFAULT 0 CHECK (booking_version>=0),
  state varchar(16) NOT NULL DEFAULT 'NONE' CHECK (state IN ('NONE','CONFIRMED','CANCELLED')),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE notification_inbox (
  event_id uuid PRIMARY KEY REFERENCES booking_outbox(id),
  disposition varchar(24) NOT NULL CHECK (disposition IN ('APPLIED','STALE_IGNORED')),
  deliveries integer NOT NULL DEFAULT 1 CHECK (deliveries>=1),
  consumed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE local_notification_receipts (
  event_id uuid PRIMARY KEY REFERENCES notification_inbox(event_id),
  booking_id uuid NOT NULL REFERENCES bookings(id),
  booking_version bigint NOT NULL,
  kind varchar(16) NOT NULL CHECK (kind IN ('CONFIRMED','CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (booking_id,booking_version)
);
