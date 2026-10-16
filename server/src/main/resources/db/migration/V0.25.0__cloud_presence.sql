ALTER TABLE asset ADD COLUMN cloud_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_asset_cloud_deleted ON asset(cloud_deleted);
