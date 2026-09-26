ALTER TABLE payments ADD COLUMN retry_started_at timestamptz;
ALTER TABLE payments ADD COLUMN retry_exhausted boolean NOT NULL DEFAULT false;
ALTER TABLE payments ADD COLUMN last_error varchar(64);
CREATE INDEX provider_backlog_age ON payments(created_at) WHERE state IN ('PENDING','UNKNOWN');
