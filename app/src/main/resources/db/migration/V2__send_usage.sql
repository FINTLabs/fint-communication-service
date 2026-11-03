CREATE TABLE send_usage (
    message_id     uuid        PRIMARY KEY,
    recipient_hash char(64)    NOT NULL,
    tenant         text        NOT NULL,
    sent_at        timestamptz NOT NULL
);

CREATE INDEX send_usage_sent_at ON send_usage (sent_at);
