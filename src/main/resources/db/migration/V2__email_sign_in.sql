CREATE TABLE email_sign_in_identities (
    email TEXT PRIMARY KEY,
    account_subject TEXT NOT NULL UNIQUE
);

CREATE TABLE email_sign_in_codes (
    email TEXT PRIMARY KEY,
    challenge_id UUID NOT NULL UNIQUE,
    browser_id TEXT NOT NULL,
    link_subject TEXT,
    code_hash BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    send_day DATE NOT NULL DEFAULT ((now() AT TIME ZONE 'UTC')::date),
    daily_sends INTEGER NOT NULL DEFAULT 1,
    attempts_left INTEGER NOT NULL CHECK (attempts_left BETWEEN 0 AND 5)
);

CREATE TABLE email_sign_in_send_limits (
    utc_day DATE PRIMARY KEY,
    sent_count INTEGER NOT NULL
);
