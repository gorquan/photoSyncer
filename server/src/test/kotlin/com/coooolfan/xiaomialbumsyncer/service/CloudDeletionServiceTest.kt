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

    @Test fun pendingDeletionRechecksRestoreEvenAfterSelectedZoneChanged() = withStore { store -> with(store) {
        val row = media.saveAssets(listOf(resource(albums[0], "A"))).single()
        addHistory(row)
        stream(mapOf("record" to CloudPresence.DELETED))
        val deselected = Crontab(job) { albumIds = emptyList() }
        var probes = 0
        val restored = deletion.confirmPending(deselected) { probes++; CloudPresence.LIVE }
        assertEquals(1, probes)
        assertEquals(1, restored.restoredGroups)
        assertTrue(deletion.deletedGroups(deselected).isEmpty())
    } }

    @Test fun concurrentAccountUpdateCannotCommitStaleDeletionPage() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        val history = sql.saveCommand(CrontabHistory { crontab = job; startTime = Instant.EPOCH; endTime = null; fetchedAllAssets = false; syncCursors = null; timelineSnapshot = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val before = media.observationRevision(job.accountId)
        media.saveAssets(listOf(resource(albums[0], "B")))
        assertThrows(IllegalArgumentException::class.java) {
            assets.commitICloudPage(job, history, "key", mutableMapOf(), ICloudChangePage("PrimarySync", "after", emptyList(), mapOf("record" to CloudPresence.DELETED)), deletion, before) {}
        }
        assertTrue(allAssets().none { it.cloudDeleted })
        assertNull(sql.findById(CrontabHistory::class, history.id)!!.syncCursors)
    } }

    @Test fun incompleteScanAndFinalFailedProbeChangeNoPresence() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A", "first"), resource(albums[0], "B", "second")))
            assertThrows(IllegalStateException::class.java) {
                deletion.reconcile(job, { _, _ -> error("interrupted final page") }, { fail<CloudPresence>("must not probe") })
            }
            var probes = 0
            assertThrows(IllegalStateException::class.java) {
                deletion.reconcile(job, { _, _ -> Unit }, {
                    if (++probes == 1) CloudPresence.DELETED else error("authorization expired")
                })
            }
            assertEquals(2, probes)
            assertTrue(allAssets().none { it.cloudDeleted })
            assertTrue(deletion.deletedGroups(job).isEmpty())
        }
    }

    @Test fun baselineAndStreamDeleteAllComponentsVersionsAndMemberships() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A"),
            resource(albums[0], "movie", role = "resOriginalVidCompl"), resource(albums[0], "alt", role = "resOriginalAlt")))
        media.saveAssets(listOf(resource(albums[0], "B")))
        val current = allAssets().associate { it.id to it.currentVersion }
        val result = baseline { CloudPresence.DELETED }
        assertEquals(1, result.newlyDeletedGroups)
        assertEquals(5, result.deletedGroups.single().assets.size)
        assertTrue(allAssets().all { it.cloudDeleted })
        assertEquals(current, allAssets().associate { it.id to it.currentVersion })
        assertTrue(assets.getAssetsUndownloadByCrontab(job, 50, 0, true).isEmpty())
        assertEquals(0, baseline { CloudPresence.DELETED }.newlyDeletedGroups)
        assertEquals(1, stream(mapOf("record" to CloudPresence.LIVE)).restoredGroups)
        assertTrue(allAssets().none { it.cloudDeleted })
        assertTrue(allAssets().filter { it.checksum == "A" }.none { it.currentVersion })
        assertEquals(1, stream(mapOf("record" to CloudPresence.DELETED)).newlyDeletedGroups)
    } }

    @Test fun baselineObservationsAndCurrentLibraryPresencePreserveMovedPhotos() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A"), resource(albums[0], "B", "moved")))
        val probes = mutableListOf<String>()
        val result = deletion.reconcileBaseline(job, "PrimarySync", setOf("record")) {
            probes += it.record; CloudPresence.LIVE
        }
        assertEquals(listOf("moved"), probes)
        assertTrue(result.deletedGroups.isEmpty())
        assertTrue(allAssets().none { it.cloudDeleted })
    } }

    @Test fun failedLastProbeAndConcurrentObservationDoNotCommitPartialDeletion() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A", "first"), resource(albums[0], "B", "second")))
        var probes = 0
        assertThrows(IllegalStateException::class.java) { baseline {
            if (++probes == 1) CloudPresence.DELETED else error("authorization expired")
        } }
        assertTrue(allAssets().none { it.cloudDeleted })
        assertThrows(IllegalArgumentException::class.java) { baseline {
            media.saveAssets(listOf(resource(albums[0], "C", "first"))); CloudPresence.DELETED
        } }
        assertTrue(allAssets().none { it.cloudDeleted })
    } }

    @Test fun scopeIncludesHistoryAndAllMembershipsButIsolatesAccountsAndZones() = withStore { store -> with(store) {
        val historical = media.saveAssets(listOf(resource(albums[1], "A"))).single()
        addHistory(historical)
        media.saveAssets(listOf(resource(albums[2], "B")))
        val other = sql.saveCommand(ProviderAccount { nickname = "other"; provider = CloudProvider.ICLOUD; userId = "other"; credentials = "{}" }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val otherAlbum = sql.saveCommand(Album { accountId = other.id; remoteKey = albums[0].remoteKey; name = "other"; shadow = false; assetCount = null; lastUpdateTime = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val otherAsset = media.saveAssets(listOf(resource(otherAlbum, "A"))).single()
        val sharedAlbum = sql.saveCommand(Album { accountId = job.accountId; remoteKey = ICloudAlbumKey("SharedSync-example", "album").encode(); name = "shared"; shadow = false; assetCount = null; lastUpdateTime = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val shared = media.saveAssets(listOf(resource(sharedAlbum, "A"))).single()
        addHistory(shared)
        val selected = Crontab(job) { albumIds = listOf(albums[0].id) }
        val result = sql.transaction { deletion.applyStates(selected, "PrimarySync", mapOf("record" to CloudPresence.DELETED)) }
        assertEquals(2, result.deletedGroups.single().assets.size)
        assertFalse(sql.findById(Asset::class, otherAsset.id)!!.cloudDeleted)
        assertFalse(sql.findById(Asset::class, shared.id)!!.cloudDeleted)
    } }

    @Test fun legacyIdentityRowsAndRestorationsOutsideSelectedAlbumsAreHandled() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A")))
        sql.executeUpdate(Asset::class) { set(table.logicalResourceKey, "") }
        baseline { CloudPresence.DELETED }
        assertTrue(allAssets().all { it.cloudDeleted })
        assertEquals(1, baseline { CloudPresence.LIVE }.restoredGroups)
        assertTrue(allAssets().none { it.cloudDeleted })
        baseline { CloudPresence.DELETED }
        media.saveAssets(listOf(resource(albums[1], "C")))
        assertTrue(allAssets().none { it.cloudDeleted })
        assertEquals(listOf("C"), assets.getAssetsUndownloadByCrontab(job, 50, 0, true).map { it.checksum })
    } }

    @Test fun pageFailureRollsBackPresenceAssetsAndCursorAndRetryIsIdempotent() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        val history = sql.saveCommand(CrontabHistory { crontab = job; startTime = Instant.EPOCH; endTime = null; fetchedAllAssets = false; syncCursors = mapOf("key" to "before"); timelineSnapshot = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val cursors = mutableMapOf("key" to "before")
        val page = ICloudChangePage("PrimarySync", "after", listOf(resource(albums[0], "new", "new")), mapOf("record" to CloudPresence.DELETED))
        source.connection.use { connection -> connection.createStatement().use {
            it.execute("CREATE TRIGGER fail_cursor BEFORE UPDATE OF album_sync_cursors ON crontab_history BEGIN SELECT RAISE(ABORT, 'checkpoint failure'); END")
        } }
        assertThrows(RuntimeException::class.java) {
            assets.commitICloudPage(job, history, "key", cursors, page, deletion) {}
        }
        source.connection.use { connection -> connection.createStatement().use { it.execute("DROP TRIGGER fail_cursor") } }
        assertEquals(mapOf("key" to "before"), cursors)
        assertEquals(mapOf("key" to "before"), sql.findById(CrontabHistory::class, history.id)!!.syncCursors)
        assertEquals(1, allAssets().size)
        assertFalse(allAssets().single().cloudDeleted)
        assets.commitICloudPage(job, history, "key", cursors, page, deletion) {}
        assertEquals("after", cursors["key"])
        assertEquals("after", sql.findById(CrontabHistory::class, history.id)!!.syncCursors!!["key"])
        assertTrue(allAssets().first { it.checksum == "A" }.cloudDeleted)
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
        assertEquals(CloudDeletionResult(), baseline { fail<CloudPresence>("Xiaomi must not probe") })
        assertTrue(deletion.deletedGroups(cursor).isEmpty())
        assertThrows(com.coooolfan.xiaomialbumsyncer.exception.BadRequestException::class.java) { CrontabService(sql).validateSyncConfig(job.accountId, CrontabSyncMode.CURSOR, job.config) }
        assertTrue(MediaDeletionService(sql).cleanup(cursor, emptyList()).isEmpty())
        CrontabService(sql).validateSyncConfig(job.accountId, CrontabSyncMode.CURSOR, job.config.copy(syncCloudDeletes = false))
        for (mode in listOf(CrontabSyncMode.FULL, CrontabSyncMode.TIMELINE))
            CrontabService(sql).validateSyncConfig(job.accountId, mode, job.config)
    } }

    @Test fun persistedTombstoneCannotBypassAuthenticationOrNetworkFailure() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A")))
            deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            assertThrows(IllegalStateException::class.java) {
                deletion.reconcile(job, { _, _ -> Unit }, { error("authorization expired") })
            }
            assertTrue(allAssets().all { it.cloudDeleted })
        }
    }

    @Test fun xiaomiUncertainStorageOnlyRetainsAnAlreadyConfirmedDeletion() = withStore { store ->
        with(store) {
            sql.executeUpdate(ProviderAccount::class) { set(table.provider, CloudProvider.XIAOMI) }
            media.saveAssets(listOf(Asset(resource(albums[0], "A")) { remoteKey = "42" }))
            assertThrows(XiaomiGalleryPresenceUncertain::class.java) {
                deletion.reconcile(job, { _, _ -> Unit }, { throw XiaomiGalleryPresenceUncertain() })
            }
            assertTrue(allAssets().none { it.cloudDeleted })
            deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            assertEquals(1, deletion.reconcile(job, { _, _ -> Unit }, { throw XiaomiGalleryPresenceUncertain() }).deletedGroups.size)
            assertEquals(1, deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.LIVE }).restoredGroups)
            assertTrue(allAssets().none { it.cloudDeleted })
        }
    }

    @Test fun backupDoesNotConsumeDeletionAndOldConfigDefaultsToBackup() = withStore { store -> with(store) {
        media.saveAssets(listOf(resource(albums[0], "A")))
        val disabled = Crontab(job) { config = job.config.copy(syncCloudDeletes = false) }
        assertEquals(CloudDeletionResult(), deletion.reconcileBaseline(disabled, "PrimarySync", emptySet()) { fail<CloudPresence>("backup must not probe") })
        assertFalse(allAssets().single().cloudDeleted)
        val old = jacksonObjectMapper().readValue<CrontabConfig>("""{"expression":"0 0 * * * ?","timeZone":"UTC","targetPath":"/out","downloadImages":true,"downloadVideos":true,"rewriteExifTime":false,"rewriteExifTimeZone":null}""")
        assertFalse(old.syncCloudDeletes)
    } }

    private fun resource(album: Album, checksum: String, record: String = "record", role: String = "resOriginal") = Asset {
        albumId = album.id; remoteKey = ICloudResourceKey(record, role).encode()
        fileName = if (role == "resOriginalVidCompl") "photo.mov" else "photo.jpg"
        type = if (role == "resOriginalVidCompl") AssetType.VIDEO else AssetType.IMAGE
        recordingType = null; dateTaken = Instant.EPOCH; this.checksum = checksum
        mimeType = if (role == "resOriginalVidCompl") "video/quicktime" else "image/jpeg"; title = "photo"; size = 10
    }

    private class Store(val source: HikariDataSource, val sql: KSqlClient, val media: CloudMediaService, val assets: AssetService,
        val deletion: CloudDeletionService, val albums: List<Album>, val job: Crontab) {
        fun baseline(probe: (com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity) -> CloudPresence) =
            deletion.reconcileBaseline(job, "PrimarySync", emptySet(), probe)
        fun stream(states: Map<String, CloudPresence>) = sql.transaction { deletion.applyStates(job, "PrimarySync", states) }
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
            action(Store(source, sql, media, AssetService(sql, xiaomi, media, icloud), CloudDeletionService(sql, xiaomi, icloud, media), albums, job))
        }
    }
}
