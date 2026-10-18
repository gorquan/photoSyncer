CREATE TABLE mirror_inventory (
    id BIGINT PRIMARY KEY NOT NULL REFERENCES crontab(id) ON DELETE CASCADE,
    albums TEXT NOT NULL,
    timeline TEXT
);

-- Indexed projection of the authoritative snapshot; updated in the same transaction.
CREATE TABLE mirror_members (
    task_id BIGINT NOT NULL REFERENCES mirror_inventory(id) ON DELETE CASCADE,
    album_id BIGINT NOT NULL,
    group_key TEXT NOT NULL,
    PRIMARY KEY (task_id, album_id, group_key)
) WITHOUT ROWID;
CREATE INDEX mirror_members_group ON mirror_members(task_id, group_key);

CREATE TRIGGER mirror_inventory_insert AFTER INSERT ON mirror_inventory BEGIN
    INSERT INTO mirror_members (task_id, album_id, group_key)
    SELECT NEW.id, CAST(albums.key AS BIGINT), members.value
    FROM json_each(NEW.albums) albums, json_each(albums.value) members;
END;

CREATE TRIGGER mirror_inventory_update AFTER UPDATE OF albums ON mirror_inventory
WHEN NEW.albums IS NOT OLD.albums BEGIN
    DELETE FROM mirror_members WHERE task_id = NEW.id;
    INSERT INTO mirror_members (task_id, album_id, group_key)
    SELECT NEW.id, CAST(albums.key AS BIGINT), members.value
    FROM json_each(NEW.albums) albums, json_each(albums.value) members;
END;

CREATE TRIGGER mirror_inventory_delete AFTER DELETE ON mirror_inventory BEGIN
    DELETE FROM mirror_members WHERE task_id = OLD.id;
END;
