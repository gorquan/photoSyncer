package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.*
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import java.nio.file.Path
import java.time.Instant

class CloudDeletionServiceTest {
    @TempDir lateinit var directory: Path

    @Test fun missingInventoryNeverDeletesOrPublishesMirrorButBackupRemainsEligible() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        assertTrue(deletion.deletedGroups(job).isEmpty())
        assertTrue(assets.getAssetsUndownloadByCrontab(job, 50, 0, true).isEmpty())
        sql.executeUpdate(Asset::class) { set(table.cloudDeleted, true) }
        val backup = Crontab(job) { config = job.config.copy(syncCloudDeletes = false) }
        assertEquals(1, assets.getAssetsUndownloadByCrontab(backup, 50, 0, true).size)
    } }

    @Test fun selectedAlbumUnionControlsPresenceRegardlessOfOtherCloudMemberships() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A"), resource(albums[2], "A")))
        val key = key("record")
        snapshot(mapOf(albums[0].id to setOf(key)))
        assertTrue(deletion.deletedGroups(job).isEmpty())
        assertEquals(listOf(albums[0].id), assets.getAssetsUndownloadByCrontab(job, 50, 0, true).map { it.album.id })
        snapshot(mapOf(albums[1].id to setOf(key)))
        assertTrue(deletion.deletedGroups(job).isEmpty())
        val result = snapshot()
        assertEquals(1, result.newlyDeletedGroups)
        assertEquals(2, result.deletedGroups.single().assets.size)
        assertTrue(allAssets().none { it.cloudDeleted })
        assertTrue(assets.getAssetsUndownloadByCrontab(job, 50, 0, true).isEmpty())
        assertEquals(1, snapshot(mapOf(albums[0].id to setOf(key))).restoredGroups)
    } }

    @Test fun allRolesAndHistoricalVersionsAreAbsentTogetherWithoutChangingSourceVersions() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A"),
            resource(albums[0], "movie", role = "resOriginalVidCompl"), resource(albums[0], "alt", role = "resOriginalAlt")))
        media.saveAssets(listOf(resource(albums[0], "B")))
        val current = allAssets().associate { it.id to it.currentVersion }
        assertEquals(5, snapshot().deletedGroups.single().assets.size)
        assertEquals(current, allAssets().associate { it.id to it.currentVersion })
        assertEquals(0, snapshot().newlyDeletedGroups)
        assertEquals(1, stream(mapOf("record" to setOf(albums[0].id))).restoredGroups)
        assertEquals(setOf("B", "movie", "alt"), assets.getAssetsUndownloadByCrontab(job, 50, 0, true).map { it.checksum }.toSet())
    } }

    @Test fun invalidScopeAndFailedTransactionPreserveLastCompleteInventory() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        snapshot(mapOf(albums[0].id to setOf(key("record"))))
        val before = deletion.inventory(job)
        assertThrows(IllegalArgumentException::class.java) { deletion.replaceSnapshot(job, emptyMap()) }
        assertThrows(IllegalStateException::class.java) { sql.transaction {
            deletion.replaceSnapshot(job, albums.take(2).associate { it.id to emptySet<String>() })
            error("interrupted commit")
        } }
        assertEquals(before, deletion.inventory(job))
        assertTrue(deletion.deletedGroups(job).isEmpty())
    } }

    @Test fun removedAlbumHistoriesRemainInCleanupScopeAndAccountsAndZonesStaySeparate() = withStore { store -> with(store) {
        val historical = media.saveAssets(listOf(resource(albums[1], "A"))).single()
        addHistory(historical)
        media.saveAssets(listOf(resource(albums[2], "B")))
        val other = sql.saveCommand(ProviderAccount { nickname = "other"; provider = CloudProvider.ICLOUD; userId = "other"; credentials = "{}" }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val otherAlbum = sql.saveCommand(Album { accountId = other.id; remoteKey = albums[0].remoteKey; name = "other"; shadow = false; assetCount = null; lastUpdateTime = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        media.saveAssets(listOf(resource(otherAlbum, "A")))
        val sharedAlbum = sql.saveCommand(Album { accountId = job.accountId; remoteKey = ICloudAlbumKey("SharedSync-example", "album").encode(); name = "shared"; shadow = false; assetCount = null; lastUpdateTime = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val shared = media.saveAssets(listOf(resource(sharedAlbum, "A"))).single()
        addHistory(shared)
        val selected = Crontab(job) { albumIds = listOf(albums[0].id) }
        sql.transaction { deletion.replaceSnapshot(selected, mapOf(albums[0].id to setOf(key("record")))) }
        assertEquals(listOf("SharedSync-example"), deletion.deletedGroups(selected).map { it.identity.zone })
        sql.transaction { deletion.replaceSnapshot(selected, mapOf(albums[0].id to emptySet())) }
        assertEquals(setOf("PrimarySync", "SharedSync-example"), deletion.deletedGroups(selected).map { it.identity.zone }.toSet())
    } }

    @Test fun cursorUpdatesSelectedMembershipAndKeepsUnchangedRecords() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[0], "B", "unchanged")))
        snapshot(mapOf(albums[0].id to setOf(key("record"), key("unchanged"))))
        assertEquals(1, stream(mapOf("record" to emptySet())).newlyDeletedGroups)
        assertEquals(setOf(key("unchanged")), deletion.inventory(job)!!.albums.getValue(albums[0].id))
        assertEquals(1, stream(mapOf("record" to setOf(albums[1].id))).restoredGroups)
        assertTrue(deletion.deletedGroups(job).isEmpty())
    } }

    @Test fun concurrentAccountUpdateCannotCommitStaleDeletionPage() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        snapshot(mapOf(albums[0].id to setOf(key("record"))))
        val history = newHistory()
        val before = media.observationRevision(job.accountId)
        media.saveAssets(listOf(resource(albums[0], "B")))
        assertThrows(IllegalArgumentException::class.java) {
            assets.commitICloudPage(job, history, "key", mutableMapOf(), ICloudChangePage("PrimarySync", "after", emptyList(), mapOf("record" to CloudPresence.DELETED)), deletion, before) {}
        }
        assertTrue(deletion.deletedGroups(job).isEmpty())
        assertNull(sql.findById(CrontabHistory::class, history.id)!!.syncCursors)
    } }

    @Test fun pageFailureRollsBackInventoryAssetsReportAndCursorAndRetryIsIdempotent() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        snapshot(mapOf(albums[0].id to setOf(key("record"))))
        val history = newHistory(mapOf("key" to "before"))
        val cursors = mutableMapOf("key" to "before")
        val page = ICloudChangePage("PrimarySync", "after", listOf(resource(albums[0], "new", "new")), mapOf("record" to CloudPresence.DELETED))
        source.connection.use { connection -> connection.createStatement().use {
            it.execute("CREATE TRIGGER fail_cursor BEFORE UPDATE OF album_sync_cursors ON crontab_history BEGIN SELECT RAISE(ABORT, 'checkpoint failure'); END")
        } }
        assertThrows(RuntimeException::class.java) {
            assets.commitICloudPage(job, history, "key", cursors, page, deletion) { CrontabService(sql).accumulateDeletionReport(history, it) }
        }
        source.connection.use { connection -> connection.createStatement().use { it.execute("DROP TRIGGER fail_cursor") } }
        assertEquals(mapOf("key" to "before"), cursors)
        assertEquals(mapOf("key" to "before"), sql.findById(CrontabHistory::class, history.id)!!.syncCursors)
        assertEquals(1, allAssets().size)
        assertTrue(deletion.deletedGroups(job).isEmpty())
        assertEquals(0, sql.findById(CrontabHistory::class, history.id)!!.deletionReport?.newlyDeletedGroups ?: 0)
        assets.commitICloudPage(job, history, "key", cursors, page, deletion) {}
        assertEquals("after", cursors["key"])
        assertEquals("after", sql.findById(CrontabHistory::class, history.id)!!.syncCursors!!["key"])
        assertEquals(listOf(key("record")), deletion.deletedGroups(job).map { it.groupKey })
        assertEquals(2, allAssets().size)
        assets.commitICloudPage(job, history, "key", cursors, page, deletion) {}
        assertEquals(2, allAssets().size)
    } }

    @Test fun xiaomiCursorMirrorIsRejectedButTimelineAndFullRemainSupported() = withStore { store -> with(store) {
        sql.executeUpdate(ProviderAccount::class) { set(table.provider, CloudProvider.XIAOMI) }
        val cursor = Crontab(job) { syncMode = CrontabSyncMode.CURSOR }
        assertFalse(deletion.enabled(cursor))
        assertTrue(deletion.enabled(job))
        assertTrue(deletion.enabled(Crontab(job) { syncMode = CrontabSyncMode.TIMELINE }))
        assertTrue(deletion.deletedGroups(cursor).isEmpty())
        assertThrows(com.coooolfan.xiaomialbumsyncer.exception.BadRequestException::class.java) { CrontabService(sql).validateSyncConfig(job.accountId, CrontabSyncMode.CURSOR, job.config) }
        assertTrue(MediaDeletionService(sql).cleanup(cursor, emptyList()).isEmpty())
        CrontabService(sql).validateSyncConfig(job.accountId, CrontabSyncMode.CURSOR, job.config.copy(syncCloudDeletes = false))
        for (mode in listOf(CrontabSyncMode.FULL, CrontabSyncMode.TIMELINE))
            CrontabService(sql).validateSyncConfig(job.accountId, mode, job.config)
    } }

    @Test fun clearingHistoryResetsMirrorBaseline() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), Asset(resource(albums[0], "audio", "audio")) { type = AssetType.AUDIO }))
        snapshot()
        assertEquals(emptyList<String>(), assets.getAssetsUndownloadByCrontab(job, 50, 0, true).map { it.checksum })
        snapshot(mapOf(albums[0].id to setOf(key("record"))))
        assertTrue(mirrorContains(sql, job.id, albums[0].id, key("record")))
        CrontabService(sql).clearCrontabHistory(job.id)
        assertNull(deletion.inventory(job))
        assertFalse(mirrorContains(sql, job.id, albums[0].id, key("record")))
        assertTrue(deletion.deletedGroups(job).isEmpty())
    } }

    @Test fun iCloudPlansRejectModeChangesAndDisablingPhotosBeforeAnyFieldsAreSaved() = withStore { store -> with(store) {
        val fetcher = org.babyfish.jimmer.sql.kt.fetcher.newFetcher(Crontab::class).by { allScalarFields() }
        val service = CrontabService(sql)
        for (patch in listOf(
            Crontab { id = job.id; syncMode = CrontabSyncMode.CURSOR; name = "must not save" },
            Crontab { id = job.id; config = job.config.copy(syncCloudDeletes = false); name = "must not save" },
        )) {
            assertThrows(com.coooolfan.xiaomialbumsyncer.exception.BadRequestException::class.java) {
                service.updateCrontab(patch, fetcher)
            }
            val persisted = sql.findById(Crontab::class, job.id)!!
            assertEquals(job.name, persisted.name)
            assertEquals(job.syncMode, persisted.syncMode)
            assertEquals(job.config, persisted.config)
        }
    } }

    @Test fun oldConfigDefaultsToBackup() {
        val old = jacksonObjectMapper().readValue<CrontabConfig>("""{"expression":"0 0 * * * ?","timeZone":"UTC","targetPath":"/out","downloadImages":true,"downloadVideos":true,"rewriteExifTime":false,"rewriteExifTimeZone":null}""")
        assertFalse(old.syncCloudDeletes)
    }

    private fun resource(album: Album, checksum: String, record: String = "record", role: String = "resOriginal") = Asset {
        albumId = album.id; remoteKey = ICloudResourceKey(record, role).encode()
        fileName = if (role == "resOriginalVidCompl") "photo.mov" else "photo.jpg"
        type = if (role == "resOriginalVidCompl") AssetType.VIDEO else AssetType.IMAGE
        recordingType = null; dateTaken = Instant.EPOCH; this.checksum = checksum
        mimeType = if (role == "resOriginalVidCompl") "video/quicktime" else "image/jpeg"; title = "photo"; size = 10
    }

    private class Store(val source: HikariDataSource, val sql: KSqlClient, val media: CloudMediaService, val assets: AssetService,
        val deletion: CloudDeletionService, val albums: List<Album>, val job: Crontab) {
        fun key(record: String) = com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity(job.accountId, CloudProvider.ICLOUD, "PrimarySync", record, "").groupKey
        fun snapshot(values: Map<Long, Set<String>> = emptyMap()) = sql.transaction {
            deletion.replaceSnapshot(job, albums.take(2).associate { it.id to values[it.id].orEmpty() })
        }
        fun stream(scopes: Map<String, Set<Long>>) = sql.transaction {
            deletion.applyPage(job, ICloudChangePage("PrimarySync", "next", emptyList(), emptyMap(), scopes))
        }
        fun newHistory(cursors: Map<String, String>? = null) = sql.saveCommand(CrontabHistory {
            crontab = job; startTime = Instant.EPOCH; endTime = null; fetchedAllAssets = false; syncCursors = cursors; timelineSnapshot = null
        }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        fun allAssets(): List<Asset> = sql.executeQuery(Asset::class) {
            orderBy(table.id.asc()); select(table.fetchBy { allScalarFields(); album { allScalarFields(); account { provider() } } })
        }
        fun addHistory(asset: Asset) {
            val history = sql.saveCommand(CrontabHistory {
                crontab = job; startTime = Instant.EPOCH; endTime = Instant.EPOCH
                fetchedAllAssets = true; syncCursors = null; timelineSnapshot = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            sql.saveCommand(CrontabHistoryDetail {
                crontabHistory = history; this.asset = asset; filePath = "/out/photo.jpg"; downloadTime = Instant.EPOCH
                downloadCompleted = true; sha1Verified = true; exifFilled = true; fsTimeUpdated = true
                publicationStatus = "PUBLISHED"; message = null
            }, SaveMode.INSERT_ONLY).execute()
        }
    }

    private fun withStore(action: (Store) -> Unit) {
        HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${directory.resolve("db.sqlite")}"; maximumPoolSize = 1 }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val sql = newKSqlClient { setDialect(SQLiteDialect()); setConnectionManager(ConnectionManager.simpleConnectionManager(source)); setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE) }
            val notify = NotifyService(sql)
            val xiaomi = XiaoMiApi(TokenManager(sql, notify))
            val icloud = ICloudPhotos(ICloudAccountService(sql, notify))
            val media = CloudMediaService(sql, xiaomi, icloud)
            val account = sql.saveCommand(ProviderAccount { nickname = "test"; provider = CloudProvider.ICLOUD; userId = "test"; credentials = "{}" }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val albums = (1..3).map { number -> sql.saveCommand(Album {
                accountId = account.id; remoteKey = ICloudAlbumKey("PrimarySync", "$number").encode(); name = "$number"
                shadow = false; assetCount = null; lastUpdateTime = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity }
            val task = sql.saveCommand(Crontab {
                name = "test"; description = ""; enabled = false; syncMode = CrontabSyncMode.FULL; accountId = account.id
                config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve("out").toString(), true, true, false, null,
                    synologyPhotos = true, syncCloudDeletes = true)
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val job = Crontab(task) { albumIds = albums.take(2).map { it.id } }
            action(Store(source, sql, media, AssetService(sql, xiaomi, media, icloud), CloudDeletionService(sql, media), albums, job))
        }
    }
}
