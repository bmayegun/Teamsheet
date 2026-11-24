CREATE TABLE clubs (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE club_members (
    club_id UUID NOT NULL REFERENCES clubs(id),
    account_subject TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'ORGANISER')),
    PRIMARY KEY (club_id, account_subject)
);

CREATE INDEX club_members_by_account ON club_members(account_subject);

CREATE TABLE club_invitations (
    token_hash TEXT PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id),
    created_by TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '7 days',
    accepted_by TEXT
);
