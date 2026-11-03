CREATE TABLE message (
    message_id     uuid        PRIMARY KEY,
    tenant         text        NOT NULL,
    channel        text        NOT NULL,
    template_id    text        NOT NULL,
    status         text        NOT NULL CONSTRAINT message_status
                               CHECK (status IN ('RECEIVED', 'SENT', 'FAILED', 'DELIVERED', 'BOUNCED', 'SUPPRESSED')),
    failure_reason text        CONSTRAINT message_failure_reason
                               CHECK (failure_reason IN ('REJECTED', 'RETRIES_EXHAUSTED', 'DELIVERY_FAILED')),
    received_at    timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT message_failure_reason_when_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

CREATE INDEX message_received_at ON message (received_at);

ALTER TABLE dispatch_queue
    ADD CONSTRAINT dispatch_queue_message FOREIGN KEY (message_id) REFERENCES message (message_id);
