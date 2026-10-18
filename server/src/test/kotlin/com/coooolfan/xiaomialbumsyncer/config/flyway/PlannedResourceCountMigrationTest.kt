package com.coooolfan.xiaomialbumsyncer.config.flyway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlannedResourceCountMigrationTest {
    @Test fun oldRunsKeepUnknownTotalAndTheirExistingDetails(@TempDir directory: Path) {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${directory.resolve("legacy.db")}"; maximumPoolSize = 1
        }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("0.27.0").load().migrate()
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=OFF")
                statement.executeUpdate("INSERT INTO crontab_history(id,crontab_id,start_time,fetched_all_assets) VALUES (1,1,0,1)")
                statement.executeUpdate("""INSERT INTO crontab_history_detail
                    (id,crontab_history_id,download_time,asset_id,file_path,download_completed,publication_status)
                    VALUES (1,1,0,1,'/legacy/photo.jpg',1,'PUBLISHED')""")
            } }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT fetched_all_assets,planned_asset_count FROM crontab_history WHERE id=1").use { rows ->
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); assertNull(rows.getObject(2))
                }
                statement.executeQuery("SELECT download_completed,publication_status FROM crontab_history_detail WHERE id=1").use { rows ->
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); assertEquals("PUBLISHED", rows.getString(2))
                }
            } }
        }
    }
}
