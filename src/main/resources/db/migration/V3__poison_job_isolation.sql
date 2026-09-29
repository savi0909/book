ALTER TABLE payments ADD COLUMN item_failures integer NOT NULL DEFAULT 0 CHECK (item_failures BETWEEN 0 AND 3);
ALTER TABLE payments ADD COLUMN quarantined_at timestamptz;
ALTER TABLE payments ADD COLUMN quarantine_reason varchar(64);
ALTER TABLE payments ADD COLUMN redrive_count integer NOT NULL DEFAULT 0 CHECK (redrive_count BETWEEN 0 AND 2);
ALTER TABLE payments ADD COLUMN poison_fixture boolean NOT NULL DEFAULT false;
CREATE INDEX isolated_recovery_candidates ON payments(next_at,id)
  WHERE state IN ('PENDING','UNKNOWN') AND NOT retry_exhausted AND quarantined_at IS NULL;
CREATE TABLE recovery_history (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES payments(id),
  action varchar(32) NOT NULL,
  reason varchar(64),
  lease_token uuid,
  request_key varchar(128),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (payment_id, request_key)
);
CREATE INDEX recovery_history_payment ON recovery_history(payment_id,id);
