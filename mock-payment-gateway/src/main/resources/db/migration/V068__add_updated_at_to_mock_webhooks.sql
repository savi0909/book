-- ============================================================================
-- V068__add_updated_at_to_mock_webhooks.sql
-- Sprint 4.1B: Fix missing updated_at column in mock_webhooks
--
-- Adds the missing updated_at column to payment_processor.mock_webhooks
-- that was accidentally omitted in V066.
-- ============================================================================

-- Add updated_at column if it doesn't exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'payment_processor'
          AND table_name = 'mock_webhooks'
          AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE payment_processor.mock_webhooks
        ADD COLUMN updated_at TIMESTAMP;
    END IF;
END $$;

COMMENT ON COLUMN payment_processor.mock_webhooks.updated_at IS 'Last update timestamp';
