ALTER TABLE crontab_history_detail ADD COLUMN verification_status TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE crontab_history_detail ADD COLUMN local_sha256 TEXT;
ALTER TABLE crontab_history_detail ADD COLUMN verified_size INTEGER;
