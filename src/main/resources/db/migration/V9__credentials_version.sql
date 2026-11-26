ALTER TABLE email_sign_in_identities ADD COLUMN credentials_version BIGINT NOT NULL DEFAULT 0;
