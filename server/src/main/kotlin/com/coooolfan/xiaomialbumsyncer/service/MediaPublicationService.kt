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
    fun legacyLayouts(task: Crontab): Map<String, List<PublicationReceipt>> =
        MediaDeletion(mediaStorageRoot()).legacyLayouts(task.id, task.config.synologyPhotos)

    fun process(details: List<CrontabHistoryDetail>, systemConfig: SystemConfig, legacyReceipts: List<PublicationReceipt>? = null) =
        MediaFileLocks.group(MediaIdentity.of(details.first().asset).groupKey) {
            try {
                requireCurrentPresence(details)
            } catch (failure: IllegalArgumentException) {
                sql.executeUpdate(CrontabHistoryDetail::class) {
                    set(table.publicationStatus, "FAILED")
                    set(table.message, failure.message)
                    where(table.id valueIn details.map { it.id })
                }
                throw failure
            }
            processLocked(details, systemConfig, legacyReceipts)
        }

    private fun requireCurrentPresence(details: List<CrontabHistoryDetail>) {
        val ids = details.map { it.asset.id }.distinct()
        val present = ids.chunked(200).flatMap { batch -> sql.executeQuery(Asset::class) {
            where(table.id valueIn batch)
            select(table.id, table.currentVersion)
        } }
        require(present.size == ids.size && (!details.first().crontabHistory.crontab.config.syncCloudDeletes || details.all {
            mirrorContains(sql, it.crontabHistory.crontab.id, it.asset.album.id, MediaIdentity.of(it.asset).groupKey)
        })) {
            "云端资源已删除或不可用，取消发布，等待删除同步"
        }
        require(present.all { it._2 }) { "来源版本已变化，取消旧版本发布，下次同步将重试" }
    }

    private fun processLocked(details: List<CrontabHistoryDetail>, systemConfig: SystemConfig, legacyReceipts: List<PublicationReceipt>?) {
        val task = details.first().crontabHistory.crontab
        val config = task.config
        val identity = MediaIdentity.of(details.first().asset)
        val root = mediaStorageRoot()
        try {
            sql.executeUpdate(CrontabHistoryDetail::class) {
                set(table.publicationStatus, "PREPARING")
                set(table.exportVersion, MediaPublisher.VERSION)
                where(table.id valueIn details.map { it.id })
            }
            val albumGroups = if (config.synologyPhotos) listOf(details) else details.groupBy { it.asset.album.id }.values.toList()
            val published = albumGroups.map { members ->
                val group = "${task.id}/${identity.groupKey}" + if (config.synologyPhotos) "" else "/${members.first().asset.album.id}"
                // Existing Photos receipts keep their fingerprint; old non-Photos EXIF exports
                // must be downloaded again once to restore the cloud-original bytes.
                val processingVersion = if (!config.synologyPhotos && config.rewriteExifTime) "cloud-original" else MediaPublisher.VERSION
                val fingerprint = jacksonObjectMapper().writeValueAsString(listOf(processingVersion, config,
                    systemConfig.exifToolPath, members.map { listOf(it.asset.remoteKey, it.asset.checksum, it.asset.size,
                        it.asset.dateTaken.toString(), it.asset.mimeType, it.asset.motionPhotoHint, it.filePath) }))
                MediaPublisher(root).publish(group, config.targetPath, fingerprint, members,
                    download = { asset, path -> media.download(task.accountId, asset, path) },
                    transform = { detail, path -> transformPublicationMedia(detail, path, systemConfig) },
                    evidence = { detail, output ->
                        sql.executeUpdate(CrontabHistoryDetail::class) {
                            set(table.downloadCompleted, true)
                            set(table.sha1Verified, true)
                            set(table.verificationStatus, output.verification)
                            set(table.localSha256, output.originalSha256)
                            set(table.verifiedSize, output.size)
                            set(table.archivePath, output.archive.takeIf { it.isNotBlank() })
                            set(table.outputSha256, output.outputSha256)
                            set(table.exifFilled, true)
                            set(table.fsTimeUpdated, true)
                            set(table.message, if (output.motionStatus == MotionPhotoStatus.STATIC && !detail.asset.motionPhotoHint) null else output.diagnostic)
                            where(table.id eq detail.id)
                        }
                    },
                    beforeCommit = { requireCurrentPresence(details) },
                    validationFailure = { detail, _ ->
                        sql.executeUpdate(CrontabHistoryDetail::class) {
                            set(table.downloadCompleted, false)
                            set(table.sha1Verified, false)
                            set(table.verificationStatus, VerificationStatus.FAILED)
                            set(table.localSha256, null)
                            set(table.verifiedSize, null)
                            where(table.id eq detail.id)
                        }
                    },
                    localReceipts = legacyReceipts.orEmpty())
            }
            if (legacyReceipts != null) {
                val cleanup = MediaDeletion(root)
                val migrated = if (config.synologyPhotos) cleanup.retireAlbumLayouts(task.id, identity.groupKey, published)
                    else cleanup.retirePhotosLayouts(task.id, identity.groupKey, published)
                require(migrated.status == "DELETED") {
                    "照片布局迁移未完成，保留未清理文件，下次同步重试：${migrated.conflicts.joinToString("；")}" +
                        if (migrated.deferredFiles > 0) "（其他计划仍引用 ${migrated.deferredFiles} 个文件）" else ""
                }
            }
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

/** Photos compatibility may fill metadata; ordinary downloads keep cloud-original bytes. */
internal fun transformPublicationMedia(detail: CrontabHistoryDetail, path: Path, systemConfig: SystemConfig) {
    val config = detail.crontabHistory.crontab.config
    if (config.synologyPhotos && config.rewriteExifTime) {
        val zone = requireNotNull(config.rewriteExifTimeZone) { "EXIF 时间填充缺少有效时区" }
        rewriteExifTime(detail.asset, path, ExifRewriteConfig(Path.of(systemConfig.exifToolPath), zone.toTimeZone()))
    }
    if (config.rewriteFileSystemTime) rewriteFSTime(path, detail.asset.dateTaken)
}
