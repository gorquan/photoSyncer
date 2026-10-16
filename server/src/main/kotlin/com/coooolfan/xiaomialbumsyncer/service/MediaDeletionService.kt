package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.MediaDeletion
import com.coooolfan.xiaomialbumsyncer.utils.MediaDeletionResult
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.valueIn
import org.noear.solon.annotation.Managed
import java.nio.file.Path

internal fun mediaStorageRoot(): Path {
    val db = Path.of(System.getenv("APP_DB_PATH") ?: "./db/xiaomialbumsyncer.db").toAbsolutePath()
    return System.getenv("PHOTO_SYNCER_MEDIA_ROOT")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: db.parent.resolve("media")
}

@Managed
class MediaDeletionService(private val sql: KSqlClient) {
    fun cleanup(crontab: Crontab, groups: List<CloudDeletionGroup>): List<MediaDeletionResult> =
        cleanup(crontab, groups, mediaStorageRoot())

    internal fun cleanup(crontab: Crontab, groups: List<CloudDeletionGroup>, root: Path): List<MediaDeletionResult> {
        if (!crontab.config.syncCloudDeletes) return emptyList()
        require(groups.all { it.identity.accountId == crontab.accountId }) { "删除资源不属于当前任务账号" }
        return groups.map { group ->
            MediaDeletion(root).delete(crontab.id, group.groupKey, stillDeleted = {
                val ids = group.assets.map { it.id }.distinct()
                val rows = ids.chunked(200).flatMap { batch -> sql.executeQuery(Asset::class) {
                    where(table.id valueIn batch)
                    select(table.id, table.cloudDeleted)
                } }
                ids.isNotEmpty() && rows.size == ids.size && rows.all { it._2 }
            })
        }
    }
}
