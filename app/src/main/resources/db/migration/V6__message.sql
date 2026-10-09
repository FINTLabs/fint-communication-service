CREATE TABLE message (
    message_id     uuid        PRIMARY KEY,
    tenant         text        NOT NULL,
    channel        text        NOT NULL,
    template_id    text        NOT NULL,
    status         text        NOT NULL CONSTRAINT message_status CHECK (status IN ('RECEIVED', 'SENT', 'FAILED')),
    failure_reason text        CONSTRAINT message_failure_reason
                               CHECK (failure_reason IN ('REJECTED', 'OPERATION_FAILED', 'RETRIES_EXHAUSTED')),
    received_at    timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT message_failure_reason_when_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

CREATE INDEX message_received_at ON message (received_at);

INSERT INTO message (message_id, tenant, channel, template_id, status, received_at, updated_at)
SELECT message_id, tenant, channel, template_id, 'RECEIVED', received_at, received_at
FROM dispatch_queue;

ALTER TABLE dispatch_queue
    DROP COLUMN tenant,
    DROP COLUMN channel,
    DROP COLUMN template_id,
    DROP COLUMN status,
    DROP COLUMN received_at,
    ADD CONSTRAINT dispatch_queue_message FOREIGN KEY (message_id) REFERENCES message (message_id);
