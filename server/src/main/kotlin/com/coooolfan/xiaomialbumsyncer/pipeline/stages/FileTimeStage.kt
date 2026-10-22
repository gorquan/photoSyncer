package com.coooolfan.xiaomialbumsyncer.pipeline.stages

import com.coooolfan.xiaomialbumsyncer.model.CrontabHistoryDetail
import com.coooolfan.xiaomialbumsyncer.model.fsTimeUpdated
import com.coooolfan.xiaomialbumsyncer.model.id
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.noear.solon.annotation.Managed
import org.slf4j.LoggerFactory

/**
 * 文件时间处理阶段处理器
 */
@Managed
class FileTimeStage(
    private val sql: KSqlClient,
) {

    private val log = LoggerFactory.getLogger(FileTimeStage::class.java)

    fun process(context: CrontabHistoryDetail): CrontabHistoryDetail {
        if (context.fsTimeUpdated) {
            log.info("资产 {} 的文件时间已更新或者被标记为无需处理，跳过文件时间阶段", context.asset.id)
            return context
        }

        // Kept as a compatibility stage for old serialized pipeline state. The
        // filesystem timestamp rewrite option was removed and is now a no-op.

        sql.executeUpdate(CrontabHistoryDetail::class) {
            set(table.fsTimeUpdated, true)
            where(table.id eq context.id)
        }
        return CrontabHistoryDetail(context) {
            fsTimeUpdated = true
        }
    }
}
