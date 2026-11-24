ALTER TABLE email_sign_in_identities ADD COLUMN password_hash TEXT;

CREATE TABLE password_sign_in_limits (
    bucket TEXT PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 1
);
