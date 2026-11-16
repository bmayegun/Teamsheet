CREATE TABLE saved_rosters (
    account_subject TEXT PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision > 0),
    payload JSONB NOT NULL
);
