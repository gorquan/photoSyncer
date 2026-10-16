package com.coooolfan.xiaomialbumsyncer.pipeline.stages

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.service.CloudMediaService
import com.coooolfan.xiaomialbumsyncer.utils.digestOriginal
import com.coooolfan.xiaomialbumsyncer.utils.validateOriginalContainer
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.noear.solon.annotation.Managed
import java.nio.file.Path

/** Stores evidence for original bytes. CloudKit fileChecksum is an opaque version signature. */
@Managed
class VerificationStage(
    private val sql: KSqlClient,
    private val media: CloudMediaService,
) {
    fun process(context: CrontabHistoryDetail): CrontabHistoryDetail {
        if (context.sha1Verified) return context
        val evidence = try {
            val path = Path.of(context.filePath)
            val digest = digestOriginal(path, context.asset.size)
            val status = if (media.isICloud(context.crontabHistory.crontab.accountId)) {
                validateOriginalContainer(path, context.asset.mimeType)
                VerificationStatus.TRANSPORT_VERIFIED
            } else {
                require(context.asset.checksum.matches(Regex("[0-9a-fA-F]{40}"))) { "小米云 SHA-1 校验值无效" }
                require(digest.sha1.equals(context.asset.checksum, ignoreCase = true)) { "小米云文件 SHA-1 不匹配" }
                VerificationStatus.SOURCE_VERIFIED
            }
            status to digest
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
            set(table.verificationStatus, evidence.first)
            set(table.localSha256, evidence.second.sha256)
            set(table.verifiedSize, evidence.second.size)
            where(table.id eq context.id)
        }
        return CrontabHistoryDetail(context) {
            sha1Verified = true
            verificationStatus = evidence.first
            localSha256 = evidence.second.sha256
            verifiedSize = evidence.second.size
        }
    }
}
