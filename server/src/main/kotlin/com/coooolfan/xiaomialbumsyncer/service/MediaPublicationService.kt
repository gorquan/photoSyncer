package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.noear.solon.annotation.Managed
import java.nio.file.Path

@Managed
class MediaPublicationService(private val sql: KSqlClient, private val media: CloudMediaService) {
    fun process(details: List<CrontabHistoryDetail>, systemConfig: SystemConfig) {
        val task = details.first().crontabHistory.crontab
        val config = task.config
        val identity = MediaIdentity.of(details.first().asset)
        val group = "${task.id}/${identity.groupKey}" + if (config.synologyPhotos) "" else "/${details.first().asset.album.id}"
        val fingerprint = jacksonObjectMapper().writeValueAsString(listOf(MediaPublisher.VERSION, config,
            systemConfig.exifToolPath, details.map { listOf(it.asset.remoteKey, it.asset.checksum, it.asset.size,
                it.asset.dateTaken.toString(), it.asset.mimeType, it.asset.motionPhotoHint, it.filePath) }))
        val db = Path.of(System.getenv("APP_DB_PATH") ?: "./db/xiaomialbumsyncer.db").toAbsolutePath()
        val root = System.getenv("PHOTO_SYNCER_MEDIA_ROOT")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: db.parent.resolve("media")
        try {
            sql.executeUpdate(CrontabHistoryDetail::class) {
                set(table.publicationStatus, "PREPARING")
                set(table.exportVersion, MediaPublisher.VERSION)
                where(table.id valueIn details.map { it.id })
            }
            MediaPublisher(root).publish(group, config.targetPath, fingerprint, details,
                download = { asset, path -> media.download(task.accountId, asset, path) },
                transform = { detail, path ->
                    if (config.rewriteExifTime) {
                        val zone = requireNotNull(config.rewriteExifTimeZone) { "EXIF 时间填充缺少有效时区" }
                        rewriteExifTime(detail.asset, path, ExifRewriteConfig(Path.of(systemConfig.exifToolPath), zone.toTimeZone()))
                    }
                    if (config.rewriteFileSystemTime) rewriteFSTime(path, detail.asset.dateTaken)
                },
                evidence = { detail, output ->
                    sql.executeUpdate(CrontabHistoryDetail::class) {
                        set(table.downloadCompleted, true)
                        set(table.sha1Verified, true)
                        set(table.verificationStatus, output.verification)
                        set(table.localSha256, output.originalSha256)
                        set(table.verifiedSize, output.size)
                        set(table.archivePath, output.archive)
                        set(table.outputSha256, output.outputSha256)
                        set(table.exifFilled, true)
                        set(table.fsTimeUpdated, true)
                        set(table.message, if (output.motionStatus == MotionPhotoStatus.STATIC && !detail.asset.motionPhotoHint) null else output.diagnostic)
                        where(table.id eq detail.id)
                    }
                },
                validationFailure = { detail, _ ->
                    sql.executeUpdate(CrontabHistoryDetail::class) {
                        set(table.downloadCompleted, false)
                        set(table.sha1Verified, false)
                        set(table.verificationStatus, VerificationStatus.FAILED)
                        set(table.localSha256, null)
                        set(table.verifiedSize, null)
                        where(table.id eq detail.id)
                    }
                })
            sql.executeUpdate(CrontabHistoryDetail::class) {
                set(table.publicationStatus, "PUBLISHED")
                where(table.id valueIn details.map { it.id })
            }
        } catch (failure: Exception) {
            sql.executeUpdate(CrontabHistoryDetail::class) {
                set(table.publicationStatus, "FAILED")
                set(table.message, failure.message ?: "资源组发布失败，将在下次同步修复")
                where(table.id valueIn details.map { it.id })
            }
            throw failure
        }
    }
}
