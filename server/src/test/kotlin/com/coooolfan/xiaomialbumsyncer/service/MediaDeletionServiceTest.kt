package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.*
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.*
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.*
import com.zaxxer.hikari.*
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.SQLiteDialect
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.runtime.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class MediaDeletionServiceTest {
    @TempDir lateinit var temporary: Path
    private val directory get() = temporary.toRealPath()
    private val jpeg by lazy { ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray() }

    @Test fun databaseRestorationAndDisabledTaskPreservePublishedBytes() = withStore { sql, media, detail ->
        val root = directory.resolve("media")
        val identity = MediaIdentity.of(detail.asset)
        val task = detail.crontabHistory.crontab
        val receipt = MediaPublisher(root).publish("${task.id}/${identity.groupKey}", task.config.targetPath, "v", listOf(detail),
            { _, path -> Files.write(path, jpeg); true }, { _, _ -> })
        val group = CloudDeletionGroup(identity, listOf(detail.asset))
        val cleanup = MediaDeletionService(sql)
        assertEquals("RESTORED", cleanup.cleanup(task, listOf(group), root).single().status)
        assertTrue(Files.exists(Path.of(receipt.outputs.single().path)))
        sql.executeUpdate(Asset::class) { set(table.cloudDeleted, true) }
        val disabled = Crontab(task) { config = task.config.copy(syncCloudDeletes = false) }
        assertTrue(cleanup.cleanup(disabled, listOf(group), root).isEmpty())
        assertTrue(Files.exists(Path.of(receipt.outputs.single().path)))
        val deleted = cleanup.cleanup(task, listOf(group), root).single()
        assertEquals("DELETED", deleted.status); assertEquals(1, deleted.exportsDeleted); assertEquals(1, deleted.originalsDeleted)
        assertFalse(Files.exists(Path.of(receipt.outputs.single().path)))
        // Re-import is authoritative restoration; the stale deletion result cannot remove new bytes.
        media.saveAssets(listOf(detail.asset))
        assertEquals("RESTORED", cleanup.cleanup(task, listOf(group), root).single().status)
    }

    @Test fun stalePublicationPageRechecksDatabaseAfterWaitingForGroupLock() = withStore { sql, media, detail ->
        val key = MediaIdentity.of(detail.asset).groupKey
        val entered = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            lateinit var publishing: java.util.concurrent.Future<String>
            MediaFileLocks.group(key) {
                publishing = pool.submit<String> {
                    entered.countDown()
                    try {
                        MediaPublicationService(sql, media).process(listOf(detail), SystemConfig { exifToolPath = "unused" })
                        "unexpected publication"
                    } catch (failure: IllegalArgumentException) { failure.message ?: "" }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                sql.executeUpdate(Asset::class) { set(table.cloudDeleted, true) }
                // The publisher cannot enter while this logical group is locked.
                assertFalse(publishing.isDone)
            }
            pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(publishing.get().contains("已删除"))
            assertEquals("FAILED", sql.findById(CrontabHistoryDetail::class, detail.id)!!.publicationStatus)
            assertFalse(Files.exists(Path.of(detail.filePath)))
            val unavailable = assertThrows(IllegalArgumentException::class.java) {
                MediaPublicationService(sql, media).process(listOf(detail), SystemConfig { exifToolPath = "unused" })
            }
            assertTrue(unavailable.message!!.contains("已删除"))
        } finally { pool.shutdownNow() }
    }

    private fun withStore(action: (KSqlClient, CloudMediaService, CrontabHistoryDetail) -> Unit) {
        HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${directory.resolve("db.sqlite")}"; maximumPoolSize = 1 }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val sql = newKSqlClient { setDialect(SQLiteDialect()); setConnectionManager(ConnectionManager.simpleConnectionManager(source)); setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE) }
            val notify = NotifyService(sql)
            val media = CloudMediaService(sql, XiaoMiApi(TokenManager(sql, notify)), ICloudPhotos(ICloudAccountService(sql, notify)))
            val account = sql.saveCommand(ProviderAccount { nickname = "test"; provider = CloudProvider.ICLOUD; userId = "test"; credentials = "{}" }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val albumRow = sql.saveCommand(Album {
                accountId = account.id; remoteKey = ICloudAlbumKey("PrimarySync", "album").encode(); name = "test"
                shadow = false; assetCount = null; lastUpdateTime = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val album = Album(albumRow) { this.account = account }
            val row = media.saveAssets(listOf(Asset {
                albumId = album.id; remoteKey = ICloudResourceKey("record", "resOriginal").encode(); checksum = "v1"
                fileName = "photo.jpg"; type = AssetType.IMAGE; mimeType = "image/jpeg"; size = jpeg.size.toLong()
                recordingType = null; dateTaken = Instant.EPOCH; title = "photo"; motionPhotoHint = false
            })).single()
            val asset = Asset(row) { this.album = album }
            val task = sql.saveCommand(Crontab {
                name = "test"; description = ""; enabled = false; syncMode = CrontabSyncMode.FULL; accountId = account.id
                config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve("out").toString(), true, true, false, null, synologyPhotos = true, syncCloudDeletes = true)
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val history = sql.saveCommand(CrontabHistory {
                crontabId = task.id; startTime = Instant.EPOCH; endTime = null; fetchedAllAssets = true; syncCursors = null; timelineSnapshot = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val detailedHistory = CrontabHistory(history) { crontab = task }
            val detailRow = sql.saveCommand(CrontabHistoryDetail(CrontabHistoryDetail.init(detailedHistory, asset)) {
                publicationStatus = "LEGACY"; message = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val detail = CrontabHistoryDetail(detailRow) { crontabHistory = detailedHistory; this.asset = asset }
            action(sql, media, detail)
        }
    }
}
