package com.coooolfan.xiaomialbumsyncer.config.flyway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MirrorInventoryMigrationTest {
    @Test fun upgradePreservesLegacyMediaAndRequiresANewTaskInventory(@TempDir directory: Path) {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${directory.resolve("legacy.db")}"; maximumPoolSize = 1
        }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("0.28.0").load().migrate()
            source.connection.use { connection -> connection.createStatement().use {
                it.execute("PRAGMA foreign_keys=OFF")
                it.executeUpdate("INSERT INTO crontab_history(id,crontab_id,start_time,fetched_all_assets) VALUES (1,1,0,1)")
                it.executeUpdate("""INSERT INTO crontab_history_detail
                    (id,crontab_history_id,download_time,asset_id,file_path,download_completed,publication_status)
                    VALUES (1,1,0,1,'/legacy/photo.jpg',1,'PUBLISHED')""")
            } }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            source.connection.use { connection -> connection.createStatement().use { statement ->
                for (table in listOf("mirror_inventory", "mirror_members")) statement.executeQuery("SELECT count(*) FROM $table").use {
                    assertTrue(it.next()); assertEquals(0, it.getInt(1))
                }
                statement.executeQuery("SELECT file_path,publication_status FROM crontab_history_detail WHERE id=1").use {
                    assertTrue(it.next()); assertEquals("/legacy/photo.jpg", it.getString(1)); assertEquals("PUBLISHED", it.getString(2))
                }
                statement.executeUpdate("""INSERT INTO mirror_inventory(id,albums) VALUES (1,'{"2":["group-a"],"3":["group-b"]}')""")
                statement.executeQuery("SELECT count(*) FROM mirror_members WHERE task_id=1").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
                connection.autoCommit = false
                statement.executeUpdate("""UPDATE mirror_inventory SET albums='{"2":[]}' WHERE id=1""")
                connection.rollback(); connection.autoCommit = true
                statement.executeQuery("SELECT count(*) FROM mirror_members WHERE task_id=1").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
                statement.executeQuery("EXPLAIN QUERY PLAN SELECT 1 FROM mirror_members WHERE task_id=1 AND album_id=2 AND group_key='group-a'").use {
                    assertTrue(it.next()); assertTrue(it.getString(4).contains("PRIMARY KEY"))
                }
                statement.executeUpdate("DELETE FROM mirror_inventory WHERE id=1")
                statement.executeQuery("SELECT count(*) FROM mirror_members").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
            } }
        }
    }
}
