package com.coooolfan.xiaomialbumsyncer.config.flyway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class VerificationEvidenceMigrationTest {
    @Test fun legacyCompletionDoesNotBecomeSourceVerification(@TempDir directory: Path) {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${directory.resolve("legacy.db")}"
            maximumPoolSize = 1
        }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("0.20.0").load().migrate()
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys=OFF")
                    statement.executeUpdate("""INSERT INTO crontab_history_detail
                        (id,crontab_history_id,download_time,asset_id,file_path,download_completed,sha1_verified,exif_filled,fs_time_updated,message)
                        VALUES (1,1,0,1,'/legacy/photo.jpg',1,1,1,1,NULL)""")
                }
            }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT download_completed,sha1_verified,verification_status,local_sha256,verified_size FROM crontab_history_detail WHERE id=1").use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean("download_completed"))
                        assertTrue(rows.getBoolean("sha1_verified"))
                        assertEquals("LEGACY", rows.getString("verification_status"))
                        assertNull(rows.getString("local_sha256"))
                        assertNull(rows.getObject("verified_size"))
                    }
                }
            }
        }
    }
}
