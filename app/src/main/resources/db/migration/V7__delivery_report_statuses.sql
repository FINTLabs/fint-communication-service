ALTER TABLE message
    DROP CONSTRAINT message_status,
    ADD CONSTRAINT message_status
        CHECK (status IN ('RECEIVED', 'SENT', 'FAILED', 'DELIVERED', 'BOUNCED', 'SUPPRESSED', 'FILTERED_SPAM')),
    DROP CONSTRAINT message_failure_reason,
    ADD CONSTRAINT message_failure_reason
        CHECK (failure_reason IN ('REJECTED', 'RETRIES_EXHAUSTED', 'DELIVERY_FAILED'));
