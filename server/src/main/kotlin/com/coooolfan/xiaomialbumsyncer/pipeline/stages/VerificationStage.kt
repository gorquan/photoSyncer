package com.coooolfan.xiaomialbumsyncer.pipeline.stages

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.service.CloudMediaService
import com.coooolfan.xiaomialbumsyncer.utils.digestOriginal
import com.coooolfan.xiaomialbumsyncer.utils.validateOriginalContainer
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.noear.solon.annotation.Managed
import java.nio.file.Path

/**
 * Verifies a downloaded source before publication.
 *
 * Apple does not expose a digest that can be compared with the downloaded bytes, so
 * iCloud verification deliberately avoids calculating a local SHA-256. The local
 * output fingerprint used later by publication and deletion remains separate.
 */
@Managed
class VerificationStage(
    private val sql: KSqlClient,
    private val media: CloudMediaService,
) {
    fun process(context: CrontabHistoryDetail): CrontabHistoryDetail {
        if (context.sha1Verified) return context
        val evidence = try {
            val path = Path.of(context.filePath)
            val status = if (media.isICloud(context.crontabHistory.crontab.accountId)) {
                val size = java.nio.file.Files.size(path)
                require(size == context.asset.size) { "文件大小不匹配：预期 ${context.asset.size}，实际 $size" }
                validateOriginalContainer(path, context.asset.mimeType)
                VerificationEvidence(VerificationStatus.TRANSPORT_VERIFIED, null, size)
            } else {
                val digest = digestOriginal(path, context.asset.size)
                require(context.asset.checksum.matches(Regex("[0-9a-fA-F]{40}"))) { "小米云 SHA-1 校验值无效" }
                require(digest.sha1.equals(context.asset.checksum, ignoreCase = true)) { "小米云文件 SHA-1 不匹配" }
                VerificationEvidence(VerificationStatus.SOURCE_VERIFIED, digest.sha256, digest.size)
            }
            status
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            // Keep an existing destination intact. Download/publication owns replacement and cleanup.
            try {
                sql.executeUpdate(CrontabHistoryDetail::class) {
                    set(table.downloadCompleted, false)
                    set(table.sha1Verified, false)
                    set(table.verificationStatus, VerificationStatus.FAILED)
                    set(table.localSha256, null)
                    set(table.verifiedSize, null)
                    where(table.id eq context.id)
                }
            } catch (updateFailure: Exception) {
                failure.addSuppressed(updateFailure)
            }
            throw failure
        }
        sql.executeUpdate(CrontabHistoryDetail::class) {
            set(table.sha1Verified, true)
            set(table.verificationStatus, evidence.status)
            set(table.localSha256, evidence.localSha256)
            set(table.verifiedSize, evidence.size)
            where(table.id eq context.id)
        }
        return CrontabHistoryDetail(context) {
            sha1Verified = true
            verificationStatus = evidence.status
            localSha256 = evidence.localSha256
            verifiedSize = evidence.size
        }
    }

    private data class VerificationEvidence(
        val status: VerificationStatus,
        val localSha256: String?,
        val size: Long,
    )
}
