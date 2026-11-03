CREATE TABLE dispatch_queue (
    message_id      uuid        PRIMARY KEY,
    tenant          text        NOT NULL,
    channel         text        NOT NULL,
    template_id     text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSING')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    locked_until    timestamptz,
    received_at     timestamptz NOT NULL,
    payload         bytea       NOT NULL
);

CREATE INDEX dispatch_queue_next_attempt_at ON dispatch_queue (next_attempt_at);
