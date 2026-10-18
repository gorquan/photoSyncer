package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.ICloudPhotos
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity
import com.coooolfan.xiaomialbumsyncer.utils.isAudioAlbum
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.XiaoMiApi
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.XiaomiGalleryPresenceUncertain
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.noear.solon.annotation.Managed

/** All persisted memberships/source versions of one provider library object. */
data class CloudDeletionGroup(val identity: MediaIdentity, val assets: List<Asset>) {
    val groupKey: String get() = identity.groupKey
}

data class CloudDeletionResult(
    val checkedGroups: Int = 0,
    val newlyDeletedGroups: Int = 0,
    val restoredGroups: Int = 0,
    val deletedGroups: List<CloudDeletionGroup> = emptyList(),
)

/** Library deletion evidence only; neither provider writes nor media downloads occur here. */
@Managed
class CloudDeletionService(
    private val sql: KSqlClient,
    private val xiaomi: XiaoMiApi,
    private val icloud: ICloudPhotos,
    private val media: CloudMediaService,
) {
    fun enabled(crontab: Crontab): Boolean = crontab.config.syncCloudDeletes &&
        (media.isICloud(crontab.accountId) || crontab.syncMode != CrontabSyncMode.CURSOR)

    fun reconcileXiaomi(crontab: Crontab): CloudDeletionResult {
        if (!enabled(crontab) || media.isICloud(crontab.accountId)) return CloudDeletionResult()
        var xiaomiDeleted: Set<String>? = null
        return reconcile(crontab, { album, handler ->
            xiaomi.fetchAssetsByAlbumId(album, strict = true, handler = handler)
            Unit
        }, { identity ->
            val deleted = xiaomiDeleted ?: xiaomi.fetchDeletedGalleryIds(identity.accountId).also { xiaomiDeleted = it }
            if (identity.record in deleted) CloudPresence.DELETED
            else xiaomi.probeGalleryPresence(identity.accountId, identity.record)
        })
    }

    /** All scan/probe IO succeeds before the transaction; a final failed probe changes no tombstones. */
    internal fun reconcile(
        crontab: Crontab,
        enumerate: (Album, (List<Asset>) -> Unit) -> Unit,
        probe: (MediaIdentity) -> CloudPresence,
    ): CloudDeletionResult {
        if (!enabled(crontab)) return CloudDeletionResult()
        val observationRevision = media.observationRevision(crontab.accountId)
        val groups = knownGroups(crontab)
        val selected = sql.executeQuery(Album::class) {
            where(table.id valueIn crontab.albumIds)
            where(table.accountId eq crontab.accountId)
            select(table.fetchBy { allScalarFields(); account { provider() } })
        }
        require(selected.size == crontab.albumIds.distinct().size) { "删除同步的相册范围不完整" }
        val live = mutableSetOf<String>()
        for (album in selected.filterNot { it.isAudioAlbum() }) {
            enumerate(album) { assets ->
                for (asset in assets) {
                    require(asset.album.id == album.id) { "删除同步扫描返回了其他相册的资产" }
                    val loaded = Asset(asset) { this.album = album }
                    live += MediaIdentity.of(loaded).groupKey
                }
            }
        }
        val states = groups.associate { group ->
            group.groupKey to when {
                group.groupKey in live -> CloudPresence.LIVE
                else -> try {
                    // A restored object may now belong to an album outside this task's selection.
                    probe(group.identity)
                } catch (uncertain: XiaomiGalleryPresenceUncertain) {
                    // A previous confirmed intent survives purge, but not an observable restoration.
                    if (group.identity.provider == CloudProvider.XIAOMI && group.assets.all { it.cloudDeleted }) CloudPresence.DELETED
                    else throw uncertain
                }
            }
        }
        synchronized(media) {
            require(media.observationRevision(crontab.accountId) == observationRevision) {
                "删除确认期间有其他同步更新了账号资产，请重试；本次保留本地文件"
            }
            sql.transaction {
                for (group in groups) {
                    val deleted = states.getValue(group.groupKey) == CloudPresence.DELETED
                    group.assets.groupBy { MediaIdentity.of(it).resourceKey }.forEach { (resourceKey, versions) ->
                        versions.map { it.id }.chunked(200).forEach { ids ->
                            sql.executeUpdate(Asset::class) {
                                set(table.cloudDeleted, deleted)
                                set(table.logicalResourceKey, resourceKey)
                                where(table.id valueIn ids)
                            }
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) media.recordPresenceObservation(crontab.accountId)
        }
        return CloudDeletionResult(
            checkedGroups = groups.size,
            newlyDeletedGroups = groups.count { states.getValue(it.groupKey) == CloudPresence.DELETED && it.assets.any { asset -> !asset.cloudDeleted } },
            restoredGroups = groups.count { states.getValue(it.groupKey) == CloudPresence.LIVE && it.assets.any { asset -> asset.cloudDeleted } },
            deletedGroups = groups.filter { states.getValue(it.groupKey) == CloudPresence.DELETED }.map { group ->
                group.copy(assets = group.assets.map { Asset(it) { cloudDeleted = true } })
            },
        )
    }

    /** Called only after a complete baseline; known records outside the selection need a library probe. */
    fun reconcileBaseline(crontab: Crontab, zone: String, observed: Set<String>): CloudDeletionResult =
        reconcileBaseline(crontab, zone, observed) { identity ->
            icloud.probePresence(identity.accountId, identity.zone, identity.record)
        }

    internal fun reconcileBaseline(
        crontab: Crontab, zone: String, observed: Set<String>, probe: (MediaIdentity) -> CloudPresence,
    ): CloudDeletionResult {
        if (!enabled(crontab) || !media.isICloud(crontab.accountId)) return CloudDeletionResult()
        val revision = media.observationRevision(crontab.accountId)
        val groups = knownGroups(crontab, zone)
        val states = groups.associate { group -> group.identity.record to
            if (group.identity.record in observed) CloudPresence.LIVE else probe(group.identity) }
        synchronized(media) {
            require(media.observationRevision(crontab.accountId) == revision) {
                "删除确认期间有其他同步更新了账号资产，请重试；本次保留本地文件"
            }
            return sql.transaction { applyStates(crontab, zone, states) }
        }
    }

    /** Retry only pending tombstones, including groups from a previously selected zone. */
    fun confirmPending(crontab: Crontab): CloudDeletionResult = confirmPending(crontab) { identity ->
        icloud.probePresence(identity.accountId, identity.zone, identity.record)
    }

    internal fun confirmPending(crontab: Crontab, probe: (MediaIdentity) -> CloudPresence): CloudDeletionResult {
        if (!enabled(crontab) || !media.isICloud(crontab.accountId)) return CloudDeletionResult()
        val revision = media.observationRevision(crontab.accountId)
        val pending = deletedGroups(crontab)
        val states = pending.associate { it.identity to probe(it.identity) }
        synchronized(media) {
            require(media.observationRevision(crontab.accountId) == revision) { "删除确认期间账号已更新，请重试；本次保留文件" }
            return sql.transaction {
                val results = states.entries.groupBy { it.key.zone }.map { (zone, entries) ->
                    applyStates(crontab, zone, entries.associate { it.key.record to it.value })
                }
                CloudDeletionResult(results.sumOf { it.checkedGroups }, results.sumOf { it.newlyDeletedGroups },
                    results.sumOf { it.restoredGroups }, results.flatMap { it.deletedGroups })
            }
        }
    }

    /** Caller commits this together with the metadata and cursor of the same changes page. */
    internal fun applyStates(crontab: Crontab, zone: String, states: Map<String, CloudPresence>): CloudDeletionResult {
        if (!enabled(crontab) || !media.isICloud(crontab.accountId) || states.isEmpty()) return CloudDeletionResult()
        if (states.size > 200) {
            val results = states.entries.chunked(200).map { batch -> applyStates(crontab, zone, batch.associate { it.key to it.value }) }
            return CloudDeletionResult(results.sumOf { it.checkedGroups }, results.sumOf { it.newlyDeletedGroups },
                results.sumOf { it.restoredGroups }, results.flatMap { it.deletedGroups })
        }
        val groups = knownGroups(crontab, zone, states.keys)
        for (group in groups) {
            val deleted = states.getValue(group.identity.record) == CloudPresence.DELETED
            group.assets.groupBy { MediaIdentity.of(it).resourceKey }.forEach { (resourceKey, versions) ->
                versions.map { it.id }.chunked(200).forEach { ids -> sql.executeUpdate(Asset::class) {
                    set(table.cloudDeleted, deleted)
                    set(table.logicalResourceKey, resourceKey)
                    where(table.id valueIn ids)
                } }
            }
        }
        if (groups.isNotEmpty()) media.recordPresenceObservation(crontab.accountId)
        return CloudDeletionResult(
            checkedGroups = groups.size,
            newlyDeletedGroups = groups.count { states.getValue(it.identity.record) == CloudPresence.DELETED && it.assets.any { a -> !a.cloudDeleted } },
            restoredGroups = groups.count { states.getValue(it.identity.record) == CloudPresence.LIVE && it.assets.any { a -> a.cloudDeleted } },
            deletedGroups = groups.filter { states.getValue(it.identity.record) == CloudPresence.DELETED }.map { group ->
                group.copy(assets = group.assets.map { Asset(it) { cloudDeleted = true } })
            },
        )
    }

    /** Retry scope includes prior task histories after the selected albums change. */
    fun deletedGroups(crontab: Crontab): List<CloudDeletionGroup> =
        if (enabled(crontab)) knownGroups(crontab, deletedOnly = true).filter { group -> group.assets.all { it.cloudDeleted } } else emptyList()

    private fun knownGroups(crontab: Crontab, zone: String? = null, records: Set<String>? = null, deletedOnly: Boolean = false): List<CloudDeletionGroup> {
        val scopedKeys = mutableSetOf<String>()
        val scopedRecords = mutableSetOf<String>()
        var lastId = 0L
        while (true) {
            val page = sql.createQuery(Asset::class) {
                where(table.album.accountId eq crontab.accountId)
                where(table.type ne AssetType.AUDIO)
                where(table.id gt lastId)
                if (zone != null) where(sql(String::class, "json_extract(%e, '$.zone')", table.album.remoteKey) eq zone)
                if (records != null) where(sql(String::class, "json_extract(%e, '$.assetId')", table.remoteKey) valueIn records)
                if (deletedOnly) where(table.cloudDeleted eq true)
                where(or(table.albumId valueIn crontab.albumIds, exists(subQuery(CrontabHistoryDetail::class) {
                    where(table.assetId eq parentTable.id)
                    where(table.crontabHistory.crontabId eq crontab.id)
                    select(table)
                })))
                orderBy(table.id.asc())
                select(table.fetchBy { allScalarFields(); album { allScalarFields(); account { provider() } } })
            }.limit(500).execute()
            if (page.isEmpty()) break
            page.forEach { asset ->
                val identity = MediaIdentity.of(asset)
                scopedKeys += identity.groupKey
                scopedRecords += identity.record
            }
            lastId = page.last().id
        }
        if (scopedKeys.isEmpty()) return emptyList()
        // Expand to every membership/checksum, including migrated rows with legacy empty identity keys.
        val rows = mutableMapOf<String, MutableList<Asset>>()
        lastId = 0L
        while (true) {
            val page = sql.createQuery(Asset::class) {
                where(table.album.accountId eq crontab.accountId)
                where(table.type ne AssetType.AUDIO)
                where(table.id gt lastId)
                if (zone != null) where(sql(String::class, "json_extract(%e, '$.zone')", table.album.remoteKey) eq zone)
                if (media.isICloud(crontab.accountId) && scopedRecords.size <= 200) where(sql(String::class, "json_extract(%e, '$.assetId')", table.remoteKey) valueIn scopedRecords)
                orderBy(table.id.asc())
                select(table.fetchBy { allScalarFields(); album { allScalarFields(); account { provider() } } })
            }.limit(500).execute()
            if (page.isEmpty()) break
            for (asset in page) {
                val key = MediaIdentity.of(asset).groupKey
                if (key in scopedKeys) rows.getOrPut(key) { mutableListOf() } += asset
            }
            lastId = page.last().id
        }
        return rows.values.map { CloudDeletionGroup(MediaIdentity.of(it.first()), it) }
    }
}
