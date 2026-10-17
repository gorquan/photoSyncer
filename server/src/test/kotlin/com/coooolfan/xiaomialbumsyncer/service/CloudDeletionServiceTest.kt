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

    @Test fun deletionIncludesAllLiveRolesMembershipsAndSourceVersions() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A"),
                resource(albums[0], "movie", role = "resOriginalVidCompl"),
                resource(albums[0], "alt", role = "resOriginalAlt")))
            media.saveAssets(listOf(resource(albums[0], "B")))
            val originalCurrent = allAssets().associate { it.id to it.currentVersion }
            val result = deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            assertEquals(1, result.checkedGroups)
            assertEquals(1, result.newlyDeletedGroups)
            assertEquals(5, result.deletedGroups.single().assets.size)
            assertTrue(allAssets().all { it.cloudDeleted })
            assertEquals(originalCurrent, allAssets().associate { it.id to it.currentVersion })
            assertTrue(assets.getAssetsUndownloadByCrontab(job, 50, 0, true).isEmpty())
            assertTrue(assets.publicationGroup(job, allAssets().first()).isEmpty())
            val retried = deletion.reconcile(job, { _, _ -> Unit }, {
                CloudPresence.DELETED
            })
            assertEquals(0, retried.newlyDeletedGroups)
            assertEquals(5, retried.deletedGroups.single().assets.size)
        }
    }

    @Test fun overlappingLiveMembershipAndMovedHiddenFavoriteRecordsRemainLive() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A"), resource(albums[0], "B", "moved")))
            val probes = mutableListOf<String>()
            val result = deletion.reconcile(job, { album, handler ->
                if (album.id == albums[1].id) handler(listOf(resource(album, "A")))
            }, { identity -> probes += identity.record; CloudPresence.LIVE })
            assertEquals(listOf("moved"), probes)
            assertTrue(result.deletedGroups.isEmpty())
            assertTrue(allAssets().none { it.cloudDeleted })
        }
    }

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

    @Test fun priorHistoriesRetainCleanupScopeAfterDeselectionAndAccountIsIsolated() = withStore { store ->
        with(store) {
            val historical = media.saveAssets(listOf(resource(albums[1], "A"))).single()
            addHistory(historical)
            media.saveAssets(listOf(resource(albums[2], "B")))
            val otherAccount = sql.saveCommand(ProviderAccount {
                nickname = "other"; provider = CloudProvider.ICLOUD; userId = "other"; credentials = "{}"
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val otherAlbum = sql.saveCommand(Album {
                accountId = otherAccount.id; remoteKey = albums[0].remoteKey; name = "other"
                shadow = false; assetCount = null; lastUpdateTime = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val other = media.saveAssets(listOf(resource(otherAlbum, "A"))).single()
            val selected = Crontab(job) { albumIds = listOf(albums[0].id) }
            val result = deletion.reconcile(selected, { _, _ -> Unit }, { CloudPresence.DELETED })
            assertEquals(2, result.deletedGroups.single().assets.size)
            assertEquals(setOf(historical.id), result.deletedGroups.single().assets.filter { it.checksum == "A" }.map { it.id }.toSet())
            assertFalse(sql.findById(Asset::class, other.id)!!.cloudDeleted)
            assertEquals(2, deletion.deletedGroups(selected).single().assets.size)
            assertTrue(CrontabService(sql).fetchAssetPathMapBy(job.id).isEmpty())
        }
    }

    @Test fun authoritativeLiveObservationRestoresPresenceWithoutReactivatingOldVersions() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A")))
            media.saveAssets(listOf(resource(albums[0], "B")))
            deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            val restored = deletion.reconcile(job, { album, handler ->
                if (album.id == albums[1].id) handler(listOf(resource(album, "B")))
            }, { fail<CloudPresence>("observed live group must not be probed") })
            assertEquals(1, restored.restoredGroups)
            assertTrue(allAssets().none { it.cloudDeleted })
            assertTrue(allAssets().filter { it.checksum == "A" }.none { it.currentVersion })
            deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            media.saveAssets(listOf(resource(albums[1], "C")))
            assertTrue(allAssets().none { it.cloudDeleted })
            assertEquals(listOf("C"), assets.getAssetsUndownloadByCrontab(job, 50, 0, true).map { it.checksum })
        }
    }

    @Test fun legacyEmptyIdentityRowsAreDeletedAndRestoredAcrossMemberships() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A"), resource(albums[1], "A")))
            sql.executeUpdate(Asset::class) { set(table.logicalResourceKey, "") }
            deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            assertTrue(allAssets().all { it.cloudDeleted })
            media.saveAssets(listOf(resource(albums[0], "B")))
            assertTrue(allAssets().none { it.cloudDeleted })
            assertTrue(allAssets().filter { it.checksum == "A" }.none { it.currentVersion })
        }
    }

    @Test fun concurrentFreshObservationAbortsDeletionWithoutOverwritingPresence() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A")))
            assertThrows(IllegalArgumentException::class.java) {
                deletion.reconcile(job, { album, _ ->
                    if (album.id == albums[0].id) media.saveAssets(listOf(resource(album, "B")))
                }, { CloudPresence.DELETED })
            }
            assertTrue(allAssets().none { it.cloudDeleted })
            assertEquals(listOf("B"), assets.getAssetsUndownloadByCrontab(job, 50, 0, true).map { it.checksum })
        }
    }

    @Test fun restoredOutsideSelectedAlbumsIsProbedEvenWithPersistedTombstone() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A")))
            deletion.reconcile(job, { _, _ -> Unit }, { CloudPresence.DELETED })
            var probes = 0
            val restored = deletion.reconcile(job, { _, _ -> Unit }, { probes++; CloudPresence.LIVE })
            assertEquals(1, probes)
            assertEquals(1, restored.restoredGroups)
            assertTrue(restored.deletedGroups.isEmpty())
            assertTrue(allAssets().none { it.cloudDeleted })
        }
    }

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

    @Test fun disabledSettingPerformsNoNetworkAndOldJsonRemainsDisabled() = withStore { store ->
        with(store) {
            media.saveAssets(listOf(resource(albums[0], "A")))
            val disabled = Crontab(job) { config = job.config.copy(syncCloudDeletes = false) }
            assertEquals(CloudDeletionResult(), deletion.reconcile(disabled,
                { _, _ -> fail<Unit>("disabled setting must not enumerate") }, { fail<CloudPresence>("disabled setting must not probe") }))
            assertFalse(allAssets().single().cloudDeleted)
            val old = jacksonObjectMapper().readValue<CrontabConfig>("""{"expression":"0 0 * * * ?","timeZone":"UTC","targetPath":"/out","downloadImages":true,"downloadVideos":true,"rewriteExifTime":false,"rewriteExifTimeZone":null}""")
            assertFalse(old.syncCloudDeletes)
        }
    }

    private fun resource(album: Album, checksum: String, record: String = "record", role: String = "resOriginal") = Asset {
        albumId = album.id; remoteKey = ICloudResourceKey(record, role).encode()
        fileName = if (role == "resOriginalVidCompl") "photo.mov" else "photo.jpg"
        type = if (role == "resOriginalVidCompl") AssetType.VIDEO else AssetType.IMAGE
        recordingType = null; dateTaken = Instant.EPOCH; this.checksum = checksum
        mimeType = if (role == "resOriginalVidCompl") "video/quicktime" else "image/jpeg"; title = "photo"; size = 10
    }

    private class Store(val sql: KSqlClient, val media: CloudMediaService, val assets: AssetService,
        val deletion: CloudDeletionService, val albums: List<Album>, val job: Crontab) {
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
            action(Store(sql, media, AssetService(sql, xiaomi, media, icloud), CloudDeletionService(sql, xiaomi, icloud, media), albums, job))
        }
    }
}
