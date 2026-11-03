CREATE TABLE dispatch_queue (
    message_id      uuid        PRIMARY KEY,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    locked_until    timestamptz,
    payload         bytea       NOT NULL
);

CREATE INDEX dispatch_queue_next_attempt_at ON dispatch_queue (next_attempt_at);
