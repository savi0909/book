-- ============================================================================
-- V066__create_mock_payment_processor_schema.sql
-- Sprint 4.1A: Mock Payment Processor Foundation
--
-- Creates a mock payment processor that simulates Razorpay for dev/test.
-- Schema: payment_processor (separate from fitpro for isolation)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS payment_processor;

SET search_path TO payment_processor, public;

-- ============================================================================
-- MOCK ORDERS (Razorpay Order equivalent)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                VARCHAR(50) NOT NULL UNIQUE,  -- order_mock_xxxxxxxxxx
    amount                  BIGINT NOT NULL,              -- Amount in paise
    amount_paid             BIGINT NOT NULL DEFAULT 0,
    amount_due              BIGINT NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'INR',
    receipt                 VARCHAR(100),                 -- FitPro order number
    status                  VARCHAR(20) NOT NULL DEFAULT 'created',  -- created/attempted/paid
    attempts                INTEGER NOT NULL DEFAULT 0,
    notes                   JSONB DEFAULT '{}'::JSONB,
    created_at              BIGINT NOT NULL,              -- Unix timestamp (Razorpay style)
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mock_orders_order_id ON payment_processor.mock_orders(order_id);
CREATE INDEX IF NOT EXISTS idx_mock_orders_receipt ON payment_processor.mock_orders(receipt);
CREATE INDEX IF NOT EXISTS idx_mock_orders_status ON payment_processor.mock_orders(status);

-- ============================================================================
-- MOCK PAYMENTS (Razorpay Payment equivalent)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_payments (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id              VARCHAR(50) NOT NULL UNIQUE,  -- pay_mock_xxxxxxxxxx
    order_id                VARCHAR(50) NOT NULL,
    amount                  BIGINT NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'INR',
    status                  VARCHAR(20) NOT NULL DEFAULT 'created',  -- created/authorized/captured/failed/refunded
    method                  VARCHAR(30) NOT NULL DEFAULT 'wallet',   -- wallet/card/upi/netbanking
    wallet                  VARCHAR(50),
    vpa                     VARCHAR(100),
    card_id                 VARCHAR(50),
    card_last4              VARCHAR(4),
    card_network            VARCHAR(20),
    card_type               VARCHAR(20),
    bank                    VARCHAR(100),
    international           BOOLEAN DEFAULT FALSE,
    amount_refunded         BIGINT DEFAULT 0,
    refund_status           VARCHAR(20),                  -- null/partial/full
    captured                BOOLEAN DEFAULT FALSE,
    captured_at             BIGINT,
    description             VARCHAR(500),
    email                   VARCHAR(255),
    contact                 VARCHAR(20),
    fee                     BIGINT DEFAULT 0,             -- Simulated gateway fee
    tax                     BIGINT DEFAULT 0,             -- GST on fee
    error_code              VARCHAR(50),
    error_description       VARCHAR(500),
    error_source            VARCHAR(50),
    error_step              VARCHAR(50),
    error_reason            VARCHAR(100),
    notes                   JSONB DEFAULT '{}'::JSONB,
    created_at              BIGINT NOT NULL,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_mock_payments_order
        FOREIGN KEY (order_id) REFERENCES payment_processor.mock_orders(order_id)
);

CREATE INDEX IF NOT EXISTS idx_mock_payments_payment_id ON payment_processor.mock_payments(payment_id);
CREATE INDEX IF NOT EXISTS idx_mock_payments_order_id ON payment_processor.mock_payments(order_id);
CREATE INDEX IF NOT EXISTS idx_mock_payments_status ON payment_processor.mock_payments(status);

-- ============================================================================
-- MOCK REFUNDS (Razorpay Refund equivalent)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_refunds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id               VARCHAR(50) NOT NULL UNIQUE,  -- rfnd_mock_xxxxxxxxxx
    payment_id              VARCHAR(50) NOT NULL,
    amount                  BIGINT NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'INR',
    receipt                 VARCHAR(100),
    status                  VARCHAR(20) NOT NULL DEFAULT 'created',  -- created/processed/failed
    speed                   VARCHAR(20) DEFAULT 'normal',            -- normal/instant
    speed_requested         VARCHAR(20) DEFAULT 'normal',
    speed_processed         VARCHAR(20),
    notes                   JSONB DEFAULT '{}'::JSONB,
    acquirer_data           JSONB DEFAULT '{}'::JSONB,    -- Bank reference numbers
    created_at              BIGINT NOT NULL,
    processed_at            BIGINT,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_mock_refunds_payment
        FOREIGN KEY (payment_id) REFERENCES payment_processor.mock_payments(payment_id)
);

CREATE INDEX IF NOT EXISTS idx_mock_refunds_refund_id ON payment_processor.mock_refunds(refund_id);
CREATE INDEX IF NOT EXISTS idx_mock_refunds_payment_id ON payment_processor.mock_refunds(payment_id);
CREATE INDEX IF NOT EXISTS idx_mock_refunds_status ON payment_processor.mock_refunds(status);

-- ============================================================================
-- MOCK WEBHOOKS (Delivery tracking)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_webhooks (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id                VARCHAR(50) NOT NULL UNIQUE,  -- evt_mock_xxxxxxxxxx
    event_type              VARCHAR(50) NOT NULL,         -- payment.captured, refund.processed, etc.
    payload                 JSONB NOT NULL,
    webhook_url             VARCHAR(500) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending/delivered/failed
    attempts                INTEGER NOT NULL DEFAULT 0,
    max_attempts            INTEGER NOT NULL DEFAULT 3,
    last_attempt_at         TIMESTAMP,
    last_response_code      INTEGER,
    last_response_body      TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at            TIMESTAMP,
    next_retry_at           TIMESTAMP,
    updated_at              TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mock_webhooks_event_id ON payment_processor.mock_webhooks(event_id);
CREATE INDEX IF NOT EXISTS idx_mock_webhooks_status ON payment_processor.mock_webhooks(status);
CREATE INDEX IF NOT EXISTS idx_mock_webhooks_pending_retry ON payment_processor.mock_webhooks(next_retry_at)
    WHERE status = 'pending';

-- ============================================================================
-- MOCK WALLETS (Simulated wallet balances)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_wallets (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id               VARCHAR(100) NOT NULL UNIQUE,  -- Phone or email
    provider                VARCHAR(50) NOT NULL DEFAULT 'mock_wallet',
    balance                 BIGINT NOT NULL DEFAULT 100000000,  -- ₹10,00,000 default
    status                  VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mock_wallets_wallet_id ON payment_processor.mock_wallets(wallet_id);

-- ============================================================================
-- MOCK WALLET TRANSACTIONS (Audit trail)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_wallet_transactions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id               VARCHAR(100) NOT NULL,
    transaction_type        VARCHAR(20) NOT NULL,         -- debit/credit/refund_credit
    amount                  BIGINT NOT NULL,
    balance_after           BIGINT NOT NULL,
    reference_type          VARCHAR(20),                  -- payment/refund/topup
    reference_id            VARCHAR(50),
    description             VARCHAR(500),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_mock_wallet_txns_wallet
        FOREIGN KEY (wallet_id) REFERENCES payment_processor.mock_wallets(wallet_id)
);

CREATE INDEX IF NOT EXISTS idx_mock_wallet_txns_wallet_id ON payment_processor.mock_wallet_transactions(wallet_id);

-- ============================================================================
-- MOCK CONFIGURATION (Runtime behavior settings)
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_processor.mock_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key              VARCHAR(100) NOT NULL UNIQUE,
    config_value            JSONB NOT NULL,
    description             VARCHAR(500),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Default configuration
INSERT INTO payment_processor.mock_config (config_key, config_value, description) VALUES
    ('failure_rate', '{"percentage": 0}', 'Random failure rate (0-100). Use magic amounts for deterministic testing.'),
    ('webhook_delay_ms', '{"min": 100, "max": 500}', 'Webhook delivery delay in milliseconds'),
    ('capture_delay_ms', '{"min": 50, "max": 200}', 'Payment capture processing delay'),
    ('magic_amounts_enabled', '{"enabled": true}', 'Enable magic amount testing'),
    ('webhook_url', '{"url": "http://localhost:8081/api/customer/api/v1/webhooks/payment"}', 'FitPro webhook endpoint'),
    ('fee_percentage', '{"rate": 2.0, "gst_rate": 18.0}', 'Payment gateway fee and GST'),
    ('auto_capture', '{"enabled": true}', 'Automatically capture payments')
ON CONFLICT (config_key) DO NOTHING;

-- Default test wallets
INSERT INTO payment_processor.mock_wallets (wallet_id, provider, balance, status) VALUES
    ('9876543210', 'mock_wallet', 100000000, 'active'),
    ('9999999999', 'mock_wallet', 100000000, 'active'),
    ('test@in', 'mock_wallet', 100000000, 'active'),
    ('low_balance@test.com', 'mock_wallet', 10000, 'active'),
    ('blocked@test.com', 'mock_wallet', 100000000, 'blocked')
ON CONFLICT (wallet_id) DO NOTHING;

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON SCHEMA payment_processor IS 'Mock Payment Processor - simulates Razorpay for dev/test';
COMMENT ON TABLE payment_processor.mock_orders IS 'Simulates Razorpay orders';
COMMENT ON TABLE payment_processor.mock_payments IS 'Simulates Razorpay payments';
COMMENT ON TABLE payment_processor.mock_refunds IS 'Simulates Razorpay refunds';
COMMENT ON TABLE payment_processor.mock_webhooks IS 'Tracks webhook delivery to FitPro';
COMMENT ON TABLE payment_processor.mock_wallets IS 'Simulated wallet balances for testing';
