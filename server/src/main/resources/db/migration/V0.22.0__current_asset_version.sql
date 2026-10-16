ALTER TABLE asset ADD COLUMN current_version BOOLEAN NOT NULL DEFAULT 1;
ALTER TABLE asset ADD COLUMN logical_resource_key TEXT NOT NULL DEFAULT '';
UPDATE asset SET logical_resource_key = (
    SELECT json_array(a.account_id, p.provider,
        CASE WHEN p.provider = 'ICLOUD' THEN json_extract(a.remote_key, '$.zone') ELSE 'library' END,
        CASE WHEN p.provider = 'ICLOUD' THEN json_extract(asset.remote_key, '$.assetId') ELSE asset.remote_key END) || '/' ||
        CASE WHEN p.provider = 'ICLOUD' THEN json_extract(asset.remote_key, '$.resource') ELSE 'original' END
    FROM album a JOIN provider_account p ON p.id = a.account_id WHERE a.id = asset.album_id
);
-- Legacy rows lack observation order: latest inserted checksum is the initial current version.
-- New observations explicitly switch versions, including a reversion to an older checksum.
UPDATE asset SET current_version = 0 WHERE checksum != (
    SELECT latest.checksum FROM asset latest WHERE latest.logical_resource_key = asset.logical_resource_key
    ORDER BY latest.id DESC LIMIT 1
);
CREATE INDEX idx_asset_current_resource ON asset(logical_resource_key, current_version);
