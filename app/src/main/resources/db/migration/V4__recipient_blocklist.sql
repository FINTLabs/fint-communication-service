CREATE TABLE recipient_blocklist (
    recipient_hash char(64)    NOT NULL,
    reason         text        NOT NULL CONSTRAINT recipient_blocklist_reason CHECK (reason IN ('OPT_OUT', 'HARD_BOUNCE')),
    source         text        NOT NULL CONSTRAINT recipient_blocklist_source CHECK (source IN ('MANUAL', 'ACS')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz,
    PRIMARY KEY (recipient_hash, reason),
    CONSTRAINT recipient_blocklist_expiry CHECK ((reason = 'OPT_OUT') = (expires_at IS NULL))
);

CREATE INDEX recipient_blocklist_expires_at ON recipient_blocklist (expires_at) WHERE expires_at IS NOT NULL;
