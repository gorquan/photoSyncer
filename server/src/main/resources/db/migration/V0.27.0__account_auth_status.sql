ALTER TABLE provider_account ADD COLUMN auth_state TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE provider_account ADD COLUMN auth_checked_at INTEGER;
