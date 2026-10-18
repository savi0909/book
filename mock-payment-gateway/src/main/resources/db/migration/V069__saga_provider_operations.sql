-- New adapter data only. Copied V066/V068 migrations remain unchanged.
CREATE TABLE payment_processor.saga_operations (
    operation_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    operation_type VARCHAR(10) NOT NULL CHECK (operation_type IN ('CAPTURE', 'REFUND')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    capture_operation_id UUID,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_id VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (order_id, operation_type),
    CHECK ((operation_type = 'CAPTURE' AND capture_operation_id IS NULL
             AND status IN ('CAPTURED', 'DECLINED', 'ACTION_REQUIRED'))
        OR (operation_type = 'REFUND' AND capture_operation_id IS NOT NULL
             AND status IN ('REFUNDED', 'REJECTED'))),
    CHECK (status NOT IN ('CAPTURED', 'REFUNDED') OR provider_id IS NOT NULL)
);

-- The result is the durable provider fact. Recovery reads it rather than rewriting it.
CREATE FUNCTION payment_processor.reject_saga_operation_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Saga provider operations are immutable';
END;
$$;
CREATE TRIGGER saga_operations_immutable BEFORE UPDATE OR DELETE
ON payment_processor.saga_operations FOR EACH ROW
EXECUTE FUNCTION payment_processor.reject_saga_operation_mutation();
