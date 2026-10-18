package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.ICloudChangePage
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudResourceKey
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity
import com.coooolfan.xiaomialbumsyncer.utils.isAudioAlbum
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.noear.solon.annotation.Managed

data class CloudDeletionGroup(val identity: MediaIdentity, val assets: List<Asset>) {
    val groupKey: String get() = identity.groupKey
}

data class CloudDeletionResult(
    val checkedGroups: Int = 0,
    val newlyDeletedGroups: Int = 0,
    val restoredGroups: Int = 0,
    val deletedGroups: List<CloudDeletionGroup> = emptyList(),
)

/** Mirror absence belongs to a task's selected albums, never to the whole provider account. */
@Managed
class CloudDeletionService(private val sql: KSqlClient, private val media: CloudMediaService) {
    fun enabled(crontab: Crontab): Boolean = crontab.config.syncCloudDeletes &&
        (media.isICloud(crontab.accountId) || crontab.syncMode != CrontabSyncMode.CURSOR)

    fun inventory(crontab: Crontab): MirrorInventory? = sql.findById(MirrorInventory::class, crontab.id)

    /** Invoke only after every required page succeeds; caller owns the transaction. */
    internal fun replaceSnapshot(
        crontab: Crontab, albums: Map<Long, Set<String>>, timeline: Map<Long, AlbumTimeline>? = null,
        zone: String? = null, records: Set<String>? = null,
    ): CloudDeletionResult {
        if (!enabled(crontab)) return CloudDeletionResult()
        val selected = sql.executeQuery(Album::class) {
            where(table.accountId eq crontab.accountId)
            where(table.id valueIn crontab.albumIds)
            select(table)
        }.filterNot { it.isAudioAlbum() }.map { it.id }.toSet()
        require(albums.keys == selected) { "镜像清单与任务相册范围不一致，本次保留文件" }
        val previous = inventory(crontab)
        val groups = knownGroups(crontab, zone, records)
        val before = previous?.albums?.values?.flatten()?.toSet() ?: groups.map { it.groupKey }.toSet()
        val after = albums.values.flatten().toSet()
        sql.saveCommand(MirrorInventory { id = crontab.id; this.albums = albums; this.timeline = timeline }, SaveMode.UPSERT).execute()
        return CloudDeletionResult(groups.size,
            groups.count { it.groupKey in before && it.groupKey !in after },
            groups.count { it.groupKey !in before && it.groupKey in after },
            groups.filter { it.groupKey !in after })
    }

    /** Atomically committed with this change page's assets, report and cursor. */
    internal fun applyPage(crontab: Crontab, page: ICloudChangePage): CloudDeletionResult {
        val previous = requireNotNull(inventory(crontab)) { "iCloud 镜像缺少完整清单，必须重新建立基线" }
        val scopes = page.scope.toMutableMap()
        // Resource rows also make manually constructed/imported pages describe their selected memberships.
        page.assets.groupBy { ICloudResourceKey.decode(it.remoteKey).assetId }.forEach { (record, rows) ->
            scopes.putIfAbsent(record, rows.map { it.album.id }.toSet())
        }
        page.presence.forEach { (record, presence) ->
            if (presence == CloudPresence.DELETED) scopes[record] = emptySet()
            else scopes.putIfAbsent(record, emptySet())
        }
        require(scopes.values.all { crontab.albumIds.containsAll(it) }) { "iCloud 变化页不属于任务所选相册" }
        val identities = scopes.mapKeys { (record, _) ->
            MediaIdentity(crontab.accountId, CloudProvider.ICLOUD, page.zone, record, "").groupKey
        }
        val albums = previous.albums.filterKeys { it in crontab.albumIds }.mapValues { (id, groups) ->
            (groups - identities.keys) + identities.filterValues { id in it }.keys
        }
        return replaceSnapshot(crontab, albums, zone = page.zone, records = scopes.keys)
    }

    /** Includes prior histories when selected albums change, and retries unfinished file cleanup. */
    fun deletedGroups(crontab: Crontab): List<CloudDeletionGroup> {
        if (!enabled(crontab)) return emptyList()
        val snapshot = inventory(crontab) ?: return emptyList()
        val live = snapshot.albums.values.flatten().toSet()
        return knownGroups(crontab).filter { it.groupKey !in live }
    }

    private fun knownGroups(crontab: Crontab, zone: String? = null, records: Set<String>? = null): List<CloudDeletionGroup> {
        val scopedAssets = mutableListOf<Asset>()
        var lastId = 0L
        while (true) {
            val page = sql.createQuery(Asset::class) {
                where(table.album.accountId eq crontab.accountId)
                where(table.type ne AssetType.AUDIO)
                where(table.id gt lastId)
                if (zone != null) where(sql(String::class, "json_extract(%e, '$.zone')", table.album.remoteKey) eq zone)
                if (records != null) where(sql(String::class, "json_extract(%e, '$.assetId')", table.remoteKey) valueIn records)
                where(or(table.albumId valueIn crontab.albumIds, exists(subQuery(CrontabHistoryDetail::class) {
                    where(table.assetId eq parentTable.id)
                    where(table.crontabHistory.crontabId eq crontab.id)
                    select(table)
                })))
                orderBy(table.id.asc())
                select(table.fetchBy { allScalarFields(); album { allScalarFields(); account { provider() } } })
            }.limit(500).execute()
            if (page.isEmpty()) break
            scopedAssets += page
            lastId = page.last().id
        }
        return scopedAssets.groupBy { MediaIdentity.of(it).groupKey }.values.map {
            CloudDeletionGroup(MediaIdentity.of(it.first()), it)
        }
    }
}
