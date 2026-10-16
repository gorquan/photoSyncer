-- Provider flags are hints only; on-disk XMP/container inspection decides compatibility.
ALTER TABLE asset ADD COLUMN motion_photo_hint BOOLEAN NOT NULL DEFAULT 0;
