ALTER TABLE crontab_history_detail ADD COLUMN publication_status TEXT NOT NULL DEFAULT 'LEGACY';
ALTER TABLE crontab_history_detail ADD COLUMN archive_path TEXT;
ALTER TABLE crontab_history_detail ADD COLUMN output_sha256 TEXT;
ALTER TABLE crontab_history_detail ADD COLUMN export_version INTEGER NOT NULL DEFAULT 0;
