CREATE TABLE authentication_network_limits (
    bucket TEXT PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1
);
