CREATE TABLE account_display_names (
    account_subject TEXT PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 100)
);
