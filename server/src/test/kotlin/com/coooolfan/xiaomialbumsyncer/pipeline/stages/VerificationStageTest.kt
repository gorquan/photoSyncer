package com.coooolfan.xiaomialbumsyncer.pipeline.stages

import com.coooolfan.xiaomialbumsyncer.icloud.ICloudAccountService
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudPhotos
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.service.CloudMediaService
import com.coooolfan.xiaomialbumsyncer.service.NotifyService
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.TokenManager
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.XiaoMiApi
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.SQLiteDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.babyfish.jimmer.sql.runtime.DefaultDatabaseNamingStrategy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

class VerificationStageTest {
    @TempDir lateinit var directory: Path
    private val content = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0, 2, 1, 2, 0xff.toByte(), 0xd9.toByte())
    private val sourceSha1 = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content))
    private val sha256 = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    @Test fun opaqueAppleSignaturesNeverBecomeRawSha1Comparisons() {
        listOf("opaque-provider-signature", "invalid-base64", Base64.getEncoder().encodeToString(byteArrayOf(1) + ByteArray(20))).forEach { signature ->
            withDetail(CloudProvider.ICLOUD, signature) { sql, stage, detail ->
                val result = stage.process(detail)
                val stored = sql.findById(CrontabHistoryDetail::class, detail.id)!!
                assertTrue(result.sha1Verified)
                assertTrue(stored.downloadCompleted)
                assertEquals(VerificationStatus.TRANSPORT_VERIFIED, stored.verificationStatus)
                assertEquals(sha256, stored.localSha256)
                assertEquals(content.size.toLong(), stored.verifiedSize)
                assertArrayEquals(content, Files.readAllBytes(Path.of(detail.filePath)))
            }
        }
    }

    @Test fun xiaomiSourceSha1IsActuallyCompared() {
        withDetail(CloudProvider.XIAOMI, sourceSha1.uppercase()) { sql, stage, detail ->
            stage.process(detail)
            val stored = sql.findById(CrontabHistoryDetail::class, detail.id)!!
            assertEquals(VerificationStatus.SOURCE_VERIFIED, stored.verificationStatus)
            assertEquals(sha256, stored.localSha256)
        }
    }

    @Test fun equalLengthXiaomiCorruptionFailsAndRetainsExistingTarget() {
        withDetail(CloudProvider.XIAOMI, sourceSha1) { sql, stage, detail ->
            val corrupted = content.copyOf().also { it[6] = 42 }
            Files.write(Path.of(detail.filePath), corrupted)
            assertThrows(IllegalArgumentException::class.java) { stage.process(detail) }
            assertFailed(sql, detail)
            assertArrayEquals(corrupted, Files.readAllBytes(Path.of(detail.filePath)))
        }
    }

    @Test fun sourceSizeMismatchFailsForBothProviders() {
        CloudProvider.entries.forEach { provider ->
            withDetail(provider, sourceSha1) { sql, stage, detail ->
                Files.write(Path.of(detail.filePath), content.dropLast(1).toByteArray())
                assertThrows(IllegalArgumentException::class.java) { stage.process(detail) }
                assertFailed(sql, detail)
            }
        }
    }

    @Test fun invalidAppleContainerFailsWithoutDeletingDestination() {
        withDetail(CloudProvider.ICLOUD, "opaque") { sql, stage, detail ->
            Files.write(Path.of(detail.filePath), ByteArray(content.size))
            assertThrows(IllegalArgumentException::class.java) { stage.process(detail) }
            assertFailed(sql, detail)
            assertTrue(Files.exists(Path.of(detail.filePath)))
        }
    }

    @Test fun unsupportedXiaomiChecksumCannotClaimSuccess() {
        withDetail(CloudProvider.XIAOMI, "not-a-sha1") { sql, stage, detail ->
            assertThrows(IllegalArgumentException::class.java) { stage.process(detail) }
            assertFailed(sql, detail)
        }
    }

    @Test fun missingFileClearsDownloadCompletion() {
        withDetail(CloudProvider.XIAOMI, sourceSha1) { sql, stage, detail ->
            Files.delete(Path.of(detail.filePath))
            assertThrows(java.nio.file.NoSuchFileException::class.java) { stage.process(detail) }
            assertFailed(sql, detail)
        }
    }

    private fun assertFailed(sql: KSqlClient, detail: CrontabHistoryDetail) {
        val stored = sql.findById(CrontabHistoryDetail::class, detail.id)!!
        assertFalse(stored.downloadCompleted)
        assertFalse(stored.sha1Verified)
        assertEquals(VerificationStatus.FAILED, stored.verificationStatus)
        assertNull(stored.localSha256)
        assertNull(stored.verifiedSize)
    }

    private fun withDetail(
        provider: CloudProvider,
        checksum: String,
        action: (KSqlClient, VerificationStage, CrontabHistoryDetail) -> Unit,
    ) {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${Files.createTempFile(directory, "verification-", ".db")}"
            maximumPoolSize = 1
        }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val sql = newKSqlClient {
                setDialect(SQLiteDialect())
                setConnectionManager(ConnectionManager.simpleConnectionManager(source))
                setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE)
            }
            val notify = NotifyService(sql)
            val media = CloudMediaService(sql, XiaoMiApi(TokenManager(sql, notify)), ICloudPhotos(ICloudAccountService(sql, notify)))
            val account = sql.saveCommand(ProviderAccount {
                this.provider = provider
                nickname = "test"
                userId = "test"
                credentials = "{}"
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val album = sql.saveCommand(Album {
                accountId = account.id
                remoteKey = "album"
                name = "album"
                assetCount = null
                lastUpdateTime = null
                shadow = false
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val asset = sql.saveCommand(Asset {
                albumId = album.id
                remoteKey = "asset"
                fileName = "photo.jpg"
                type = AssetType.IMAGE
                recordingType = null
                dateTaken = Instant.EPOCH
                this.checksum = checksum
                mimeType = "image/jpeg"
                title = "photo"
                size = content.size.toLong()
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val crontab = sql.saveCommand(Crontab {
                accountId = account.id
                name = "test"
                description = ""
                enabled = false
                syncMode = CrontabSyncMode.FULL
                config = CrontabConfig("0 0 * * * ?", "UTC", directory.toString(), true, true, false)
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val history = sql.saveCommand(CrontabHistory {
                crontabId = crontab.id
                startTime = Instant.EPOCH
                endTime = null
                timelineSnapshot = null
                syncCursors = null
                fetchedAllAssets = true
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val file = Files.createTempFile(directory, "photo-", ".jpg")
            Files.write(file, content)
            val detail = sql.saveCommand(CrontabHistoryDetail {
                crontabHistoryId = history.id
                assetId = asset.id
                downloadTime = Instant.EPOCH
                filePath = file.toString()
                downloadCompleted = true
                sha1Verified = false
                exifFilled = true
                fsTimeUpdated = true
                message = null
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val context = CrontabHistoryDetail(detail) {
                this.asset = asset
                crontabHistory = CrontabHistory(history) { this.crontab = crontab }
            }
            action(sql, VerificationStage(sql, media), context)
        }
    }
}
