package com.coooolfan.xiaomialbumsyncer.config.flyway

import com.coooolfan.xiaomialbumsyncer.icloud.ICloudAlbumKey
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudResourceKey
import com.coooolfan.xiaomialbumsyncer.model.CloudProvider
import com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MediaIdentityMigrationTest {
    @Test fun legacyRowsGetCanonicalIdentityAndCurrentChecksumAcrossMemberships(@TempDir directory: Path) {
        HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${directory.resolve("migration.db")}"; maximumPoolSize = 1 }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").target("0.21.0").load().migrate()
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO provider_account(id,nickname,user_id,provider,credentials) VALUES (20,'Apple','test','ICLOUD','{}')")
                    for (id in 1..2) connection.prepareStatement("INSERT INTO album(id,remote_key,name,account_id,shadow) VALUES (?,?,'album',20,0)").use {
                        it.setInt(1, id); it.setString(2, ICloudAlbumKey("PrimarySync", "$id").encode()); it.executeUpdate()
                    }
                    for ((id, album, checksum) in listOf(Triple(1,1,"A"), Triple(2,2,"A"), Triple(3,1,"B"))) {
                        connection.prepareStatement("INSERT INTO asset(id,remote_key,file_name,type,date_taken,album_id,checksum,mime_type,title,size) VALUES (?,?,'legacy.jpg','IMAGE',0,?,?,'image/jpeg','title',10)").use {
                            it.setInt(1,id); it.setString(2,ICloudResourceKey("record", "resOriginal").encode()); it.setInt(3,album); it.setString(4,checksum); it.executeUpdate()
                        }
                    }
                }
            }
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            source.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id,logical_resource_key,current_version FROM asset ORDER BY id").use { rows ->
                    var count = 0
                    while (rows.next()) {
                        assertEquals(MediaIdentity(20,CloudProvider.ICLOUD,"PrimarySync","record","resOriginal").resourceKey,rows.getString(2))
                        assertEquals(rows.getInt(1) == 3,rows.getBoolean(3)); count++
                    }
                    assertEquals(3,count)
                }
            } }
        }
    }
}
