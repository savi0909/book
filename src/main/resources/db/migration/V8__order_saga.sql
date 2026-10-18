CREATE TABLE orders (
 id uuid PRIMARY KEY, customer_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN ('PENDING','CONFIRMED','CANCELLING','CANCELLED','FAILED','MANUAL_REVIEW')),
 show_id uuid NOT NULL, items jsonb NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0),
 currency varchar(3) NOT NULL CHECK(currency='INR'),
 quantity integer GENERATED ALWAYS AS (jsonb_array_length(items)) STORED CHECK(quantity BETWEEN 1 AND 10), version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX orders_customer_idx ON orders(customer_id,created_at DESC);
CREATE TABLE order_requests (
 customer_id uuid NOT NULL, idempotency_key varchar(128) NOT NULL, request_hash text NOT NULL,
 order_id uuid NOT NULL REFERENCES orders(id), response_body jsonb NOT NULL,
 response_status integer NOT NULL CHECK(response_status=202), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(customer_id,idempotency_key)
);
CREATE TABLE order_sagas (
 id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
 phase text NOT NULL CHECK(phase IN ('RESERVING','PAYING','COMMITTING','COMPENSATING','COMPLETED','CANCELLED','FAILED','MANUAL_REVIEW')),
 inventory_state text NOT NULL DEFAULT 'UNKNOWN' CHECK(inventory_state IN ('UNKNOWN','HELD','COMMITTED','RELEASED','REJECTED','EXPIRED')),
 payment_state text NOT NULL DEFAULT 'UNKNOWN' CHECK(payment_state IN ('UNKNOWN','PENDING','ACTION_REQUIRED','CAPTURED','DECLINED','CANCELLED','REFUNDED')),
 reserve_command_id uuid NOT NULL UNIQUE, capture_command_id uuid NOT NULL UNIQUE,
 reservation_id uuid, reservation_expires_at timestamptz, payment_id text,
 cancel_requested boolean NOT NULL DEFAULT false, failure_requested boolean NOT NULL DEFAULT false,
 deadline_at timestamptz NOT NULL, next_action_at timestamptz, recovery_attempts integer NOT NULL DEFAULT 0 CHECK(recovery_attempts>=0),
 last_error text, inventory_version bigint NOT NULL DEFAULT -1, recovery_generation integer NOT NULL DEFAULT 0 CHECK(recovery_generation>=0), version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(phase NOT IN ('COMPLETED','CANCELLED','FAILED','MANUAL_REVIEW') OR next_action_at IS NULL)
);
CREATE INDEX order_saga_due_idx ON order_sagas(next_action_at,id) WHERE phase IN ('RESERVING','PAYING','COMMITTING','COMPENSATING');
CREATE TABLE order_commands (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id), saga_id uuid NOT NULL REFERENCES order_sagas(id),
 type text NOT NULL, payload jsonb NOT NULL, generation integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE order_outbox (
 id uuid PRIMARY KEY, aggregate_id uuid NOT NULL REFERENCES orders(id), saga_id uuid NOT NULL REFERENCES order_sagas(id),
 dedupe_key text NOT NULL UNIQUE, event_type text NOT NULL, destination text NOT NULL,
 payload jsonb NOT NULL, status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','IN_PROGRESS','PUBLISHED','DEAD')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 locked_by text, lock_token uuid, locked_until timestamptz, last_error text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), published_at timestamptz,
 CHECK((status='IN_PROGRESS')=(locked_by IS NOT NULL AND lock_token IS NOT NULL AND locked_until IS NOT NULL)),
 CHECK(status='IN_PROGRESS' OR (locked_by IS NULL AND lock_token IS NULL AND locked_until IS NULL))
);
CREATE INDEX order_outbox_ready_idx ON order_outbox(available_at,id) WHERE status='PENDING';
CREATE INDEX order_outbox_expired_idx ON order_outbox(locked_until,id) WHERE status='IN_PROGRESS';
CREATE INDEX order_outbox_order_idx ON order_outbox(aggregate_id,created_at);
CREATE INDEX order_outbox_dead_idx ON order_outbox(updated_at,id) WHERE status='DEAD';
CREATE INDEX order_outbox_cleanup_idx ON order_outbox(published_at,id) WHERE status='PUBLISHED';
CREATE TABLE order_inbox (
 consumer_name text NOT NULL, message_id uuid NOT NULL, payload_hash text NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(consumer_name,message_id)
);
CREATE INDEX order_inbox_cleanup_idx ON order_inbox(processed_at);
CREATE TABLE order_message_failures (
 topic text NOT NULL, partition_id integer NOT NULL, offset_id bigint NOT NULL,
 payload text NOT NULL, attempts integer NOT NULL DEFAULT 1, quarantined boolean NOT NULL DEFAULT false,
 last_error text NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(topic,partition_id,offset_id)
);
CREATE TABLE order_recovery_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id),
 event_id uuid, actor text NOT NULL, reason text NOT NULL, prior_attempts integer,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE order_inventory (
 reserve_command_id uuid PRIMARY KEY, order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
 booking_id uuid UNIQUE REFERENCES movie_bookings(id),
 state text NOT NULL CHECK(state IN ('HELD','COMMITTED','RELEASED','REJECTED','EXPIRED')),
 stop_requested boolean NOT NULL DEFAULT false, expires_at timestamptz,
 version bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX order_inventory_expiry_idx ON order_inventory(expires_at,reserve_command_id) WHERE state='HELD';
CREATE TABLE order_payment_operations (
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES orders(id), capture_command_id uuid NOT NULL,
 operation_type text NOT NULL CHECK(operation_type IN ('CAPTURE','REFUND')),
 generation integer NOT NULL DEFAULT 0 CHECK(generation=0), provider_key text NOT NULL UNIQUE,
 request_hash text NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0), currency varchar(3) NOT NULL CHECK(currency='INR'),
 status text NOT NULL CHECK(status IN ('PENDING','UNKNOWN','ACTION_REQUIRED','CAPTURED','DECLINED','CANCELLED','REFUNDED','MANUAL_REVIEW')),
 stop_requested boolean NOT NULL DEFAULT false, provider_id text,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), next_action_at timestamptz,
 lock_token uuid, locked_until timestamptz, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(order_id,operation_type,generation), CHECK((lock_token IS NULL)=(locked_until IS NULL))
);
CREATE INDEX order_payment_due_idx ON order_payment_operations(next_action_at,id) WHERE status IN ('PENDING','UNKNOWN','ACTION_REQUIRED');
CREATE FUNCTION order_immutable_message() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.aggregate_id,NEW.saga_id,NEW.dedupe_key,NEW.event_type,NEW.destination,NEW.payload,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.aggregate_id,OLD.saga_id,OLD.dedupe_key,OLD.event_type,OLD.destination,OLD.payload,OLD.created_at)
 THEN RAISE EXCEPTION 'Outbox identity and payload are immutable'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER order_outbox_immutable BEFORE UPDATE ON order_outbox FOR EACH ROW EXECUTE FUNCTION order_immutable_message();
