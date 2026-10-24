package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.MediaDeletion
import com.coooolfan.xiaomialbumsyncer.utils.MediaDeletionResult
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.noear.solon.annotation.Managed
import java.nio.file.Path

internal fun mediaStorageRoot(): Path {
    val db = Path.of(System.getenv("APP_DB_PATH") ?: "./db/xiaomialbumsyncer.db").toAbsolutePath()
    return System.getenv("PHOTO_SYNCER_MEDIA_ROOT")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: (db.parent ?: Path.of(".")).resolve("media")
}

@Managed
class MediaDeletionService(private val sql: KSqlClient) {
    fun cleanup(crontab: Crontab, groups: List<CloudDeletionGroup>): List<MediaDeletionResult> =
        cleanup(crontab, groups, mediaStorageRoot())

    internal fun cleanup(crontab: Crontab, groups: List<CloudDeletionGroup>, root: Path): List<MediaDeletionResult> {
        if (!crontab.config.syncCloudDeletes || (crontab.syncMode == CrontabSyncMode.CURSOR &&
            sql.findById(ProviderAccount::class, crontab.accountId)?.provider == CloudProvider.XIAOMI)) return emptyList()
        require(groups.all { it.identity.accountId == crontab.accountId }) { "删除资源不属于当前任务账号" }
        return groups.map { group ->
            MediaDeletion(root).delete(crontab.id, group.groupKey, stillDeleted = {
                mirrorAbsent(sql, crontab.id, group.groupKey)
            })
        }
    }
}

/** Missing inventories fail closed for mirror publication and cleanup. */
internal fun mirrorContains(sql: KSqlClient, taskId: Long, albumId: Long, groupKey: String): Boolean =
    sql.createQuery(MirrorInventory::class) {
        where(table.id eq taskId)
        where(sql(Boolean::class,
            "exists (select 1 from mirror_members mm where mm.task_id = %e and mm.album_id = %e and mm.group_key = %e)",
            table.id, value(albumId), value(groupKey)))
        select(table.id)
    }.execute().isNotEmpty()

private fun mirrorAbsent(sql: KSqlClient, taskId: Long, groupKey: String): Boolean =
    sql.createQuery(MirrorInventory::class) {
        where(table.id eq taskId)
        where(sql(Boolean::class,
            "not exists (select 1 from mirror_members mm where mm.task_id = %e and mm.group_key = %e)",
            table.id, value(groupKey)))
        select(table.id)
    }.execute().isNotEmpty()
