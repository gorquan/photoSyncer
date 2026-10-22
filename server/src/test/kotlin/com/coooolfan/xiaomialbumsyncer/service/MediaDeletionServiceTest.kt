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

    @Test fun cursorMirrorCommitsPresenceThenCleansFilesAndRestoresWithoutAnArchive() = withStore { sql, media, detail ->
        val root = directory.resolve("cursor-media")
        val identity = MediaIdentity.of(detail.asset)
        val task = Crontab(detail.crontabHistory.crontab) { albumIds = listOf(detail.asset.album.id); syncMode = CrontabSyncMode.CURSOR }
        val history = detail.crontabHistory
        val key = "mirror-test"
        val cursors = mutableMapOf(key to "before")
        val notify = NotifyService(sql)
        val icloud = ICloudPhotos(ICloudAccountService(sql, notify))
        val deletion = CloudDeletionService(sql, media)
        val assets = AssetService(sql, XiaoMiApi(TokenManager(sql, notify)), media, icloud)
        fun publish() = MediaPublisher(root).publish("${task.id}/${identity.groupKey}", task.config.targetPath, "v", listOf(detail),
            { _, path -> Files.write(path, jpeg); true }, { _, _ -> })
        val output = Path.of(publish().outputs.single().path)
        assertTrue(Files.exists(output))
        assets.commitICloudPage(task, history, key, cursors, ICloudChangePage(identity.zone, "deleted", emptyList(), mapOf(identity.record to CloudPresence.DELETED)), deletion) {
            CrontabService(sql).accumulateDeletionReport(history, it)
        }
        assertEquals("deleted", sql.findById(CrontabHistory::class, history.id)!!.syncCursors!![key])
        assertTrue(Files.exists(output), "checkpoint persistence does not delete files")
        val cleanup = MediaDeletionService(sql)
        val deleted = cleanup.cleanup(task, deletion.deletedGroups(task), root).single()
        assertEquals(1, deleted.exportsDeleted)
        assertFalse(Files.exists(output))
        assertTrue(cleanup.cleanup(task, deletion.deletedGroups(task), root).all { it.exportsDeleted == 0 })
        assets.commitICloudPage(task, history, key, cursors, ICloudChangePage(identity.zone, "restored", listOf(detail.asset), mapOf(identity.record to CloudPresence.LIVE)), deletion) {}
        assertTrue(deletion.deletedGroups(task).isEmpty())
        publish()
        assertArrayEquals(jpeg, Files.readAllBytes(output))
        assertFalse(Files.exists(root.resolve("originals")))
    }

    @Test fun photosSwitchControlsByteChangingMetadataProcessing() = withStore { _, _, detail ->
        val marker = directory.resolve("exif-called")
        val tool = directory.resolve("exiftool")
        Files.writeString(tool, "#!/bin/sh\ntouch '$marker'\nfor arg do target=\"${'$'}arg\"; done\nprintf x >> \"${'$'}target\"\n")
        assertTrue(tool.toFile().setExecutable(true))
        for (photos in listOf(false, true)) {
            val configured = CrontabHistoryDetail(detail) {
                crontabHistory = CrontabHistory(detail.crontabHistory) {
                    crontab = Crontab(detail.crontabHistory.crontab) {
                        config = detail.crontabHistory.crontab.config.copy(synologyPhotos = photos,
                            rewriteExifTime = true, rewriteExifTimeZone = "UTC", rewriteFileSystemTime = true)
                    }
                }
            }
            val path = Files.write(directory.resolve("photo-$photos.jpg"), jpeg)
            transformPublicationMedia(configured, path, SystemConfig { exifToolPath = tool.toString() })
            assertEquals(photos, Files.exists(marker))
            if (photos) assertArrayEquals(jpeg + byteArrayOf('x'.code.toByte()), Files.readAllBytes(path))
            else assertArrayEquals(jpeg, Files.readAllBytes(path))
            assertTrue(Files.getLastModifiedTime(path).toInstant() != Instant.EPOCH)
        }
    }

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
        sql.saveCommand(MirrorInventory { id = task.id; albums = mapOf(detail.asset.album.id to emptySet()); timeline = null }, SaveMode.UPSERT).execute()
        val disabled = Crontab(task) { config = task.config.copy(syncCloudDeletes = false) }
        assertTrue(cleanup.cleanup(disabled, listOf(group), root).isEmpty())
        assertTrue(Files.exists(Path.of(receipt.outputs.single().path)))
        val deleted = cleanup.cleanup(task, listOf(group), root).single()
        assertEquals("DELETED", deleted.status); assertEquals(1, deleted.exportsDeleted); assertEquals(0, deleted.originalsDeleted)
        assertFalse(Files.exists(Path.of(receipt.outputs.single().path)))
        // Re-import is authoritative restoration; the stale deletion result cannot remove new bytes.
        media.saveAssets(listOf(detail.asset))
        sql.saveCommand(MirrorInventory { id = task.id; albums = mapOf(detail.asset.album.id to setOf(identity.groupKey)); timeline = null }, SaveMode.UPSERT).execute()
        assertEquals("RESTORED", cleanup.cleanup(task, listOf(group), root).single().status)
    }

    @Test fun stalePublicationPageRechecksDatabaseAfterWaitingForGroupLock() = withStore { sql, media, detail ->
        val task = detail.crontabHistory.crontab
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
                sql.saveCommand(MirrorInventory { id = task.id; albums = mapOf(detail.asset.album.id to emptySet()); timeline = null }, SaveMode.UPSERT).execute()
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

    @Test fun supersededSourceVersionIsRejectedBeforeAnyDownload() = withStore { sql, media, detail ->
        sql.executeUpdate(Asset::class) { set(table.currentVersion, false) }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MediaPublicationService(sql, media).process(listOf(detail), SystemConfig { exifToolPath = "unused" })
        }
        assertTrue(failure.message!!.contains("版本"), failure.message)
        assertEquals("FAILED", sql.findById(CrontabHistoryDetail::class, detail.id)!!.publicationStatus)
        assertFalse(Files.exists(Path.of(detail.filePath)))
    }

    @Test fun incompleteTimelineHistoryCannotReplaceLastFetchedBaseline() = withStore { sql, _, detail ->
        val task = detail.crontabHistory.crontab
        val snapshots = (1L..3L).map { number -> mapOf(1L to AlbumTimeline("hash-$number", emptyMap())) }
        val fetched = sql.saveCommand(CrontabHistory {
            crontabId = task.id; startTime = Instant.ofEpochSecond(1); endTime = Instant.ofEpochSecond(2)
            fetchedAllAssets = true; timelineSnapshot = snapshots[0]; executionStatus = "PARTIAL"
        }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        sql.saveCommand(CrontabHistory {
            crontabId = task.id; startTime = Instant.ofEpochSecond(3); endTime = Instant.ofEpochSecond(4)
            fetchedAllAssets = false; timelineSnapshot = snapshots[1]; executionStatus = "FAILED"
        }, SaveMode.INSERT_ONLY).execute()
        val running = sql.saveCommand(CrontabHistory {
            crontabId = task.id; startTime = Instant.ofEpochSecond(5); endTime = null
            fetchedAllAssets = false; timelineSnapshot = snapshots[2]; executionStatus = "RUNNING"
        }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val service = CrontabService(sql)
        assertEquals(snapshots[0], service.getAlbumTimelinesHistory(CrontabHistory(running) { crontab = task }))
        sql.executeDelete(CrontabHistory::class) { where(table.id eq fetched.id) }
        assertTrue(service.getAlbumTimelinesHistory(CrontabHistory(running) { crontab = task }).isEmpty())
    }

    @Test fun initialTimelineBaselineCannotIncludeUploadsAfterEnumerationStarted() = withStore { sql, media, detail ->
        val notify = NotifyService(sql)
        val assets = AssetService(sql, XiaoMiApi(TokenManager(sql, notify)), media, ICloudPhotos(ICloudAccountService(sql, notify)))
        val day = java.time.LocalDate.of(2026, 10, 10)
        val initial = mapOf(1L to AlbumTimeline("before-upload", mapOf(day to 1L)))
        val uploaded = mapOf(1L to AlbumTimeline("after-upload", mapOf(day to 2L)))
        var remote = initial
        assets.refreshWithTimelineSnapshot(detail.crontabHistory, snapshot = { remote }) {
            // An upload after the enumeration's last page was read must be fetched next run.
            remote = uploaded
        }
        val saved = sql.findById(CrontabHistory::class, detail.crontabHistory.id)!!.timelineSnapshot!!
        assertEquals(initial, saved)
        assertEquals(setOf(day), remote.getValue(1L).daysToRefreshSince(saved.getValue(1L)))
        assertThrows(IllegalStateException::class.java) {
            assets.refreshWithTimelineSnapshot(detail.crontabHistory, snapshot = { remote }) { error("interrupted enumeration") }
        }
        assertEquals(initial, sql.findById(CrontabHistory::class, detail.crontabHistory.id)!!.timelineSnapshot)
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
            sql.saveCommand(MirrorInventory { id = task.id; albums = mapOf(album.id to setOf(MediaIdentity.of(asset).groupKey)); timeline = null }, SaveMode.INSERT_ONLY).execute()
            val detailedHistory = CrontabHistory(history) { crontab = task }
            val detailRow = sql.saveCommand(CrontabHistoryDetail(CrontabHistoryDetail.init(detailedHistory, asset)) {
                publicationStatus = "LEGACY"; message = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val detail = CrontabHistoryDetail(detailRow) { crontabHistory = detailedHistory; this.asset = asset }
            action(sql, media, detail)
        }
    }
}
