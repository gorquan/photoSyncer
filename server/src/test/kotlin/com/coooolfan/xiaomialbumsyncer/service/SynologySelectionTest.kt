package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.*
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity
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

class SynologySelectionTest {
    @TempDir lateinit var directory: Path

    @Test fun currentVersionsReversionsAndAlbumOverlapAreSelectedBeforePaging() = withStore { sql, media, service, albums ->
        fun image(album: Album, checksum: String, record: String = "record") = resource(album, checksum, record, "resOriginal")
        val first = media.saveAssets(listOf(image(albums[0], "A"))).single()
        media.saveAssets(listOf(image(albums[1], "A")))
        media.saveAssets(listOf(image(albums[0], "B")))
        val job = job(albums)
        assertEquals(listOf("B"), service.getAssetsUndownloadByCrontab(job, 10, 0).map { it.checksum })
        media.saveAssets(listOf(image(albums[0], "A")))
        val selected = service.getAssetsUndownloadByCrontab(job, 1, 0)
        assertEquals(first.id, selected.single().id)
        assertTrue(service.getAssetsUndownloadByCrontab(job, 1, first.id).isEmpty())
        assertEquals("A", service.getAssetsUndownloadByCrontab(job(listOf(albums[1])), 10, 0).single().checksum)
        val native = Crontab(job) { config = job.config.copy(synologyPhotos = false) }
        assertEquals(2, service.getAssetsUndownloadByCrontab(native, 10, 0).size)
        // Different remote records with identical bytes remain separate assets.
        media.saveAssets(listOf(image(albums[0], "A", "different")))
        assertEquals(2, service.getAssetsUndownloadByCrontab(job, 10, 0).size)
        assertEquals(1, sql.executeQuery(Asset::class) { where(table.checksum eq "B"); where(table.currentVersion eq false); select(table) }.size)
    }

    @Test fun companionSwitchAndSafePathsWorkForLegacyNamesWithoutMetadataRefresh() = withStore { _, media, service, albums ->
        media.saveAssets(listOf(resource(albums[0], "photo", "live", "resOriginal"),
            resource(albums[0], "movie", "live", "resOriginalVidCompl"),
            resource(albums[0], "alt", "live", "resOriginalAlt"),
            resource(albums[0], "ordinary", "video", "resOriginal", video = true)))
        val job = job(albums)
        val photosOnly = Crontab(job) { config = job.config.copy(downloadVideos = false) }
        val selected = service.getAssetsUndownloadByCrontab(photosOnly, 10, 0)
        assertEquals(3, selected.size)
        val history = CrontabHistory { id = 1; crontab = photosOnly; startTime = Instant.EPOCH }
        val primary = selected.first { it.checksum == "photo" }
        val companion = selected.first { it.checksum == "movie" }
        val primaryPath = Path.of(CrontabHistoryDetail.init(history, primary).filePath)
        val companionPath = Path.of(CrontabHistoryDetail.init(history, companion).filePath)
        assertEquals(primaryPath.parent, companionPath.parent)
        assertEquals(primaryPath.fileName.toString().substringBeforeLast('.'), companionPath.fileName.toString().substringBeforeLast('.'))
        assertNotEquals(MediaIdentity.of(primary).stem, MediaIdentity.of(selected.first { it.checksum == "alt" }).stem)
        val disabled = Crontab(job) { config = job.config.copy(downloadLivePhotos = false) }
        assertEquals(setOf("photo", "alt", "ordinary"), service.getAssetsUndownloadByCrontab(disabled, 10, 0).map { it.checksum }.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            photosOnly.config.copy(targetPath = "/data/\${assetId}/\${checksum}")
        }
        val ordinaryLayout = CrontabHistory(history) { crontab = Crontab(photosOnly) { config = photosOnly.config.copy(synologyPhotos = false) } }
        assertEquals(Path.of(CrontabHistoryDetail.init(ordinaryLayout, primary).filePath).fileName.toString().substringBeforeLast('.'),
            Path.of(CrontabHistoryDetail.init(ordinaryLayout, companion).filePath).fileName.toString().substringBeforeLast('.'))
    }

    @Test fun refreshingOnlyNoncanonicalAlbumUpdatesSharedMetadataWithoutChangingMemberships() = withStore { sql, media, service, albums ->
        val original = resource(albums[0], "same-content", "record", "resOriginal")
        val first = media.saveAssets(listOf(original)).single()
        media.saveAssets(listOf(Asset(original) { albumId = albums[1].id }))
        val changedTime = Instant.parse("2026-10-17T01:02:03Z")
        media.saveAssets(listOf(Asset(original) {
            albumId = albums[1].id; dateTaken = changedTime; motionPhotoHint = true
            fileName = "updated.jpeg"; title = "updated"; mimeType = "image/jpeg"; size = 12
        }))
        val selected = service.getAssetsUndownloadByCrontab(job(albums), 10, 0).single()
        assertEquals(first.id, selected.id)
        assertEquals(albums[0].id, selected.album.id)
        assertEquals(changedTime, selected.dateTaken)
        assertEquals("updated.jpeg", selected.fileName)
        assertEquals("updated", selected.title)
        assertEquals(12L, selected.size)
        assertTrue(selected.motionPhotoHint)
        // Generic imports may update only a loaded field. Other shared scalars stay intact.
        media.saveAssets(listOf(Asset {
            albumId = albums[1].id; remoteKey = original.remoteKey; checksum = original.checksum; title = "partial"
        }))
        val memberships = sql.executeQuery(Asset::class) {
            where(table.checksum eq "same-content"); select(table)
        }
        assertEquals(2, memberships.size)
        assertTrue(memberships.all { it.title == "partial" && it.dateTaken == changedTime && it.size == 12L && it.motionPhotoHint })
    }

    @Test fun xiaomiOverlapUsesRemoteIdentityRatherThanByteChecksum() = withStore(CloudProvider.XIAOMI) { _, media, service, albums ->
        fun native(album: Album, key: String) = Asset(resource(album, "same-sha1", "unused", "resOriginal")) { remoteKey = key }
        media.saveAssets(listOf(native(albums[0], "remote-1"), native(albums[1], "remote-1"), native(albums[0], "remote-2")))
        val selected = service.getAssetsUndownloadByCrontab(job(albums), 10, 0)
        assertEquals(setOf("remote-1", "remote-2"), selected.map { it.remoteKey }.toSet())
        assertEquals(2, selected.size)
        assertNotEquals(MediaIdentity.of(selected[0]).stem, MediaIdentity.of(selected[1]).stem)
    }

    @Test fun authoritativeAppleObservationRetiresMissingRolesAcrossMemberships() = withStore { _, media, service, albums ->
        val primary = resource(albums[0], "photo", "live", "resOriginal")
        media.saveAssets(listOf(primary, resource(albums[0], "movie", "live", "resOriginalVidCompl"),
            resource(albums[1], "movie", "live", "resOriginalVidCompl"), resource(albums[0], "alt", "live", "resOriginalAlt")))
        // Partial imports do not claim an authoritative role set.
        media.saveAssets(listOf(primary))
        assertEquals(3, service.getAssetsUndownloadByCrontab(job(albums), 10, 0).size)
        media.saveAssets(listOf(Asset(primary) { checksum = "still-v2" }), authoritativeAppleRoles = true)
        val selected = service.getAssetsUndownloadByCrontab(job(albums), 10, 0)
        assertEquals(listOf("still-v2"), selected.map { it.checksum })
        assertEquals(1, service.publicationGroup(job(albums), selected.single()).size)
    }

    @Test fun manualMetadataPatchUsesLatestPublishedPathOwnerAndCurrentVersion() = withStore { sql, media, _, albums ->
        val config = job(albums).config
        val task = sql.saveCommand(Crontab {
            name = "manual"; description = ""; enabled = false; syncMode = CrontabSyncMode.FULL
            accountId = albums[0].accountId; this.config = config
        }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        val history = sql.saveCommand(CrontabHistory {
            crontab = task; startTime = Instant.EPOCH; endTime = Instant.EPOCH
            fetchedAllAssets = true; syncCursors = null; timelineSnapshot = null
        }, SaveMode.INSERT_ONLY).execute().modifiedEntity
        fun publish(asset: Asset, path: String) {
            sql.saveCommand(CrontabHistoryDetail {
                crontabHistory = history; this.asset = asset; filePath = path
                downloadTime = Instant.EPOCH
                downloadCompleted = true; sha1Verified = true; exifFilled = true; fsTimeUpdated = true
                publicationStatus = "PUBLISHED"; message = null
            }, SaveMode.INSERT_ONLY).execute()
        }
        val input = resource(albums[0], "A", "record", "resOriginal")
        val first = media.saveAssets(listOf(input)).single()
        val path = directory.resolve("out/photo.jpg").toString()
        publish(first, path)
        val second = media.saveAssets(listOf(Asset(input) { checksum = "B" })).single()
        publish(second, path)
        val service = CrontabService(sql)
        assertEquals(setOf(second.id), service.fetchAssetPathMapBy(task.id).keys.map { it.id }.toSet())
        media.saveAssets(listOf(input)) // A is current again, but B still owns the physical export.
        assertTrue(service.fetchAssetPathMapBy(task.id).isEmpty())
        publish(first, path)
        assertEquals(setOf(first.id), service.fetchAssetPathMapBy(task.id).keys.map { it.id }.toSet())
    }

    @Test fun oldJsonRetainsLayoutWhileEnablingLiveCompanions() {
        val config = jacksonObjectMapper().readValue<CrontabConfig>("""{"expression":"0 0 * * * ?","timeZone":"UTC","targetPath":"/data/\u0024{album}/\u0024{fileName}","downloadImages":true,"downloadVideos":false,"rewriteExifTime":false,"rewriteExifTimeZone":null}""")
        assertFalse(config.synologyPhotos)
        assertTrue(config.downloadLivePhotos)
        assertFalse(config.checkSha1)
    }

    private fun resource(album: Album, checksum: String, record: String, role: String, video: Boolean = false) = Asset {
        albumId = album.id
        remoteKey = ICloudResourceKey(record, role).encode()
        fileName = "legacy_${checksum}.${if (role == "resOriginalVidCompl" || video) "mov" else "jpg"}"
        type = if (role == "resOriginalVidCompl" || video) AssetType.VIDEO else AssetType.IMAGE
        recordingType = null
        dateTaken = Instant.EPOCH
        this.checksum = checksum
        mimeType = if (type == AssetType.VIDEO) "video/quicktime" else "image/jpeg"
        title = "legacy"
        size = 10
    }

    private fun job(selected: List<Album>) = Crontab {
        id = 1; accountId = selected.first().accountId; name = "test"; albumIds = selected.map { it.id }
        config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve("out").toString(), true, true, false, null, synologyPhotos = true)
    }

    private fun withStore(provider: CloudProvider = CloudProvider.ICLOUD, action: (KSqlClient, CloudMediaService, AssetService, List<Album>) -> Unit) {
        HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${directory.resolve("db.sqlite")}"; maximumPoolSize = 1 }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val sql = newKSqlClient { setDialect(SQLiteDialect()); setConnectionManager(ConnectionManager.simpleConnectionManager(source)); setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE) }
            val notify = NotifyService(sql)
            val xiaomi = XiaoMiApi(TokenManager(sql, notify))
            val icloud = ICloudPhotos(ICloudAccountService(sql, notify))
            val media = CloudMediaService(sql, xiaomi, icloud)
            val account = sql.saveCommand(ProviderAccount { nickname = "test"; this.provider = provider; userId = "test"; credentials = "{}" }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val albums = (1..2).map { number -> sql.saveCommand(Album {
                accountId = account.id; remoteKey = if (provider == CloudProvider.ICLOUD) ICloudAlbumKey("PrimarySync", "$number").encode() else "$number"; name = "$number"; shadow = false; assetCount = null; lastUpdateTime = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity }
            action(sql, media, AssetService(sql, xiaomi, media, icloud), albums)
        }
    }
}
