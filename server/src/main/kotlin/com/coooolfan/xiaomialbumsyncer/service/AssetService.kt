package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudAlbumKey
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudPhotos
import com.coooolfan.xiaomialbumsyncer.utils.isAudioAlbum
import com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.AlbumSyncInfo
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.XiaoMiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.kt.ast.query.KMutableRootQuery
import org.babyfish.jimmer.sql.kt.ast.table.KNonNullTable
import org.noear.solon.annotation.Managed
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate

@Managed
class AssetService(private val sql: KSqlClient, private val api: XiaoMiApi, private val media: CloudMediaService, private val icloud: ICloudPhotos) {

    private val log = LoggerFactory.getLogger(AssetService::class.java)

    fun refreshAssets(albumId: Long, fetcher: Fetcher<Asset>): List<Asset> {
        val album = sql.executeQuery(Album::class) {
            where(table.id eq albumId)
            select(table)
        }.firstOrNull() ?: throw IllegalArgumentException("Album $albumId not found, please refresh albums first")

        media.fetchAssets(album) { media.saveAssets(it, authoritativeAppleRoles = media.isICloud(album.accountId)) }

        return getAssets(albumId, fetcher)
    }

    fun refreshAssetsByDiffTimeline(
        crontab: Crontab,
        crontabHistory: CrontabHistory,
        albumTimelinesHistory: Map<Long, AlbumTimeline>
    ) {
        val accountId = crontab.accountId

        // 获取相册列表（需要 remoteKey）
        val albums = crontabAlbums(crontab)

        // 1. 获取这些相册最新的 timeline
        val albumTimelinesLatest = fetchAlbumsTimelineSnapshot(accountId, albums)
        val storageTimelineStartAt = Instant.now()
        sql.executeUpdate(CrontabHistory::class) {
            set(table.timelineSnapshot, albumTimelinesLatest)
            where(table.id eq crontabHistory.id)
        }
        log.info(
            "持久化 CrontabHistory ${crontabHistory.id} 的 timelineSnapshot，耗时 {} ms",
            Instant.now().toEpochMilli() - storageTimelineStartAt.toEpochMilli()
        )

        // 2. 对比 timeline，找出有变更的日期
        // remoteID -> AlbumTimeline
        val compareDiffStartAt = Instant.now()
        val albumsDayCountNeedRefresh = mutableMapOf<Long, Set<LocalDate>>()
        for ((albumRemoteId, timelineLatest) in albumTimelinesLatest) {
            val timelineHistory = albumTimelinesHistory[albumRemoteId] ?: EMPTY_ALBUM_TIMELINE
            albumsDayCountNeedRefresh[albumRemoteId] = timelineLatest.daysToRefreshSince(timelineHistory)
            sql.executeUpdate(Album::class) {
                set(table.assetCount, timelineLatest.dayCount.values.sum())
                where(table.remoteKey eq albumRemoteId.toString())
                where(table.accountId eq accountId)
            }
        }
        log.info(
            "对比 CrontabHistory ${crontabHistory.id} 的 timelineSnapshot，耗时 {} ms",
            Instant.now().toEpochMilli() - compareDiffStartAt.toEpochMilli()
        )

        // 3. 只刷新这些日期的 Asset（并发执行）
        runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(10)
            albumsDayCountNeedRefresh.flatMap { (albumRemoteId, dayList) ->
                val album = sql.executeQuery(Album::class) {
                    where(table.remoteKey eq albumRemoteId.toString())
                    where(table.accountId eq accountId)
                    select(table)
                }.firstOrNull() ?: throw IllegalStateException("Cannot find album $albumRemoteId")

                dayList.map { day ->
                    async {
                        semaphore.withPermit {
                            log.info("开始刷新相册 {} 的 {} 日的 Asset", albumRemoteId, day)
                            api.fetchAssetsByAlbumId(album, day) { assets ->
                                media.saveAssets(assets)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun refreshAssetsFull(
        crontab: Crontab,
        crontabHistory: CrontabHistory,
        captureTimelineSnapshot: Boolean = false,
    ) {
        val albums = crontabAlbums(crontab)
        if (media.isICloud(crontab.accountId)) refreshICloudAlbumCounts(albums)

        refreshWithTimelineSnapshot(crontabHistory,
            snapshot = if (captureTimelineSnapshot) ({ fetchAlbumsTimelineSnapshot(crontab.accountId, albums) }) else null,
        ) {
            runBlocking(Dispatchers.IO) {
                val semaphore = Semaphore(5)
                albums.map { album ->
                    async { semaphore.withPermit { refreshAlbumFull(album) } }
                }.awaitAll()
            }
        }
    }

    internal fun refreshWithTimelineSnapshot(history: CrontabHistory, snapshot: (() -> Map<Long, AlbumTimeline>)?, refresh: () -> Unit) {
        // Capture before enumeration, so uploads during pagination remain visible to the next run.
        val baseline = snapshot?.invoke()
        refresh()
        if (baseline != null) {
            sql.executeUpdate(CrontabHistory::class) {
                set(table.timelineSnapshot, baseline)
                where(table.id eq history.id)
            }
        }
    }

    /**
     * 位点同步模式：album/full 预检筛出变化的相册，allitems 按位点拉流。
     * 位点为空即全量回放；每页提交后把位点写回当前 CrontabHistory，崩溃后下次运行可续拉。
     */
    fun refreshAssetsBySyncTag(
        crontab: Crontab,
        crontabHistory: CrontabHistory,
        cursorBaseline: Map<String, String>
    ) {
        val accountId = crontab.accountId

        // 录音不在相册位点体系内，维持全量路径
        val (audioAlbums, galleryAlbums) = crontabAlbums(crontab).partition { it.isAudioAlbum() }
        audioAlbums.forEach(::refreshAlbumFull)
        if (galleryAlbums.isEmpty()) return

        // 预检：一次请求取全部相册水位头
        val remoteInfos = api.fetchAlbumSyncSnapshot(accountId).associateBy { it.albumId }
        // 各相册并发拉取，位点写回经由该 Map 的锁串行化，保证落库的总是最新快照
        val cursors = cursorBaseline.toMutableMap()

        runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(5)
            galleryAlbums.map { album ->
                async {
                    semaphore.withPermit {
                        val remote = remoteInfos[album.remoteKey.toLong()]
                        if (remote == null) {
                            log.warn("相册 {} (remoteKey={}) 未出现在 album/full 快照中，本次跳过", album.name, album.remoteKey)
                        } else {
                            refreshAlbumBySyncTag(album, remote, cursorBaseline[album.remoteKey]) { syncTag ->
                                synchronized(cursors) {
                                    cursors[album.remoteKey] = syncTag
                                    sql.executeUpdate(CrontabHistory::class) {
                                        set(table.syncCursors, cursors.toMap())
                                        where(table.id eq crontabHistory.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Full and timeline mirrors publish only a complete, task-selected cloud inventory. */
    fun refreshMirrorInventory(crontab: Crontab, history: CrontabHistory, deletion: CloudDeletionService): CloudDeletionResult {
        val albums = sql.executeQuery(Album::class) {
            where(table.id valueIn crontab.albumIds)
            where(table.accountId eq crontab.accountId)
            select(table.fetchBy { allScalarFields(); account { provider() } })
        }
        require(albums.size == crontab.albumIds.distinct().size) { "镜像相册范围不完整，本次保留文件" }
        val gallery = albums.filterNot { it.isAudioAlbum() }
        val timeline = if (crontab.syncMode == CrontabSyncMode.TIMELINE)
            fetchAlbumsTimelineSnapshot(crontab.accountId, gallery) else null
        val previous = deletion.inventory(crontab)
        val observed = java.util.concurrent.ConcurrentHashMap<Long, Set<String>>()
        runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(5)
            albums.map { album -> async { semaphore.withPermit {
                if (album.isAudioAlbum()) {
                    refreshAlbumFull(album)
                } else {
                    val remote = album.remoteKey.toLongOrNull()
                    val latest = timeline?.get(remote)
                    val before = previous?.timeline?.get(remote)
                    val retained = previous?.albums?.get(album.id)
                    if (latest != null && before != null && retained != null && latest.indexHash == before.indexHash) {
                        observed[album.id] = retained
                    } else {
                        val groups = mutableSetOf<String>()
                        fun save(rows: List<Asset>) {
                            require(rows.all { it.album.id == album.id }) { "镜像分页返回其他相册的资源" }
                            groups += rows.map { MediaIdentity.of(Asset(it) { this.album = album }).groupKey }
                            media.saveAssets(rows, authoritativeAppleRoles = media.isICloud(crontab.accountId))
                        }
                        if (latest != null && before != null && retained != null) {
                            // Hash changes require every current nonempty day, even when counts stayed equal.
                            for (day in latest.daysToRefreshSince(before))
                                api.fetchAssetsByAlbumId(album, day, strict = true, handler = ::save)
                        } else if (media.isICloud(crontab.accountId)) {
                            icloud.fetchAssetsForMirror(album, ::save)
                        } else {
                            api.fetchAssetsByAlbumId(album, strict = true, handler = ::save)
                        }
                        if (latest != null) require(groups.size.toLong() == latest.dayCount.values.sum()) {
                            "小米镜像清单数量与时间线不一致，请重试；本次保留文件"
                        }
                        observed[album.id] = groups.toSet()
                    }
                }
            } } }.awaitAll()
        }
        return sql.transaction {
            val result = deletion.replaceSnapshot(crontab, observed.toMap(), timeline)
            observed.forEach { (albumId, groups) -> sql.executeUpdate(Album::class) {
                set(table.assetCount, groups.size.toLong())
                where(table.id eq albumId)
            } }
            if (timeline != null) sql.executeUpdate(CrontabHistory::class) {
                set(table.timelineSnapshot, timeline)
                where(table.id eq history.id)
            }
            result
        }
    }

    fun refreshICloudAssetsByCursor(
        crontab: Crontab, history: CrontabHistory, baseline: Map<String, String>,
        deletion: CloudDeletionService? = null, recordDeletion: (CloudDeletionResult) -> Unit = {},
    ): Boolean {
        if (deletion == null) {
            refreshICloudBackupByCursor(crontab, history, baseline)
            return false
        }
        val albums = crontabAlbums(crontab)
        refreshICloudAlbumCounts(albums)
        val cursors = baseline.toMutableMap()
        val snapshot = deletion.inventory(crontab)
        val scopeMatches = snapshot?.albums?.keys == albums.map { it.id }.toSet()
        var allEstablished = true
        for ((zone, selected) in albums.groupBy { ICloudAlbumKey.decode(it.remoteKey).zone }) {
            val key = "icloud:${crontab.accountId}:$zone:${selected.map { it.id }.sorted().joinToString(",")}:mirror-v2"
            val observed = selected.associate { it.id to mutableSetOf<String>() }
            var revision = media.observationRevision(crontab.accountId)
            val established = icloud.fetchIncrementalPages(selected, if (scopeMatches) baseline[key] else null, true,
                saveBaseline = { rows ->
                    rows.forEach { row -> observed.getValue(row.album.id) += MediaIdentity(crontab.accountId,
                        CloudProvider.ICLOUD, zone, com.coooolfan.xiaomialbumsyncer.icloud.ICloudResourceKey.decode(row.remoteKey).assetId, "").groupKey }
                    media.saveAssets(rows, authoritativeAppleRoles = true)
                },
                baselineReady = {
                    sql.transaction {
                        val current = deletion.inventory(crontab)?.albums.orEmpty().filterKeys { id -> id in crontab.albumIds }
                        val complete = albums.associate { it.id to current[it.id].orEmpty() } + observed.mapValues { it.value.toSet() }
                        recordDeletion(deletion.replaceSnapshot(crontab, complete, zone = zone))
                    }
                },
                beforePage = { revision = media.observationRevision(crontab.accountId) },
                consume = { page -> commitICloudPage(crontab, history, key, cursors, page, deletion, revision, recordDeletion) },
            )
            allEstablished = allEstablished && established
        }
        return allEstablished
    }

    private fun refreshICloudBackupByCursor(crontab: Crontab, history: CrontabHistory, baseline: Map<String, String>) {
        val albums = crontabAlbums(crontab)
        refreshICloudAlbumCounts(albums)
        val cursors = baseline.toMutableMap()
        for ((zone, selected) in albums.groupBy { ICloudAlbumKey.decode(it.remoteKey).zone }) {
            // 相册范围改变后建立新基线；账号之间、任务之间不共享同步进度。
            val key = "icloud:${crontab.accountId}:$zone:${selected.map { it.id }.sorted().joinToString(",")}"
            icloud.fetchIncremental(selected, baseline[key], { media.saveAssets(it, authoritativeAppleRoles = true) }) { token ->
                cursors[key] = token
                sql.executeUpdate(CrontabHistory::class) {
                    set(table.syncCursors, cursors.toMap())
                    where(table.id eq history.id)
                }
            }
        }
    }

    internal fun commitICloudPage(
        crontab: Crontab, history: CrontabHistory, key: String, cursors: MutableMap<String, String>,
        page: com.coooolfan.xiaomialbumsyncer.icloud.ICloudChangePage,
        deletion: CloudDeletionService?, expectedRevision: Long? = null, recordDeletion: (CloudDeletionResult) -> Unit,
    ) {
        val next = cursors + (key to page.cursor)
        synchronized(media) {
            require(expectedRevision == null || media.observationRevision(crontab.accountId) == expectedRevision) {
                "iCloud 变化确认期间有其他同步更新了账号，请重试；本页位点未推进"
            }
            sql.transaction {
                // Apply presence first so restoration counts retain the previous tombstone state.
                if (deletion?.enabled(crontab) == true) recordDeletion(deletion.applyPage(crontab, page))
                media.saveAssets(page.assets, authoritativeAppleRoles = true)
                sql.executeUpdate(CrontabHistory::class) {
                    set(table.syncCursors, next)
                    where(table.id eq history.id)
                }
            }
        }
        cursors[key] = page.cursor
    }

    private fun refreshICloudAlbumCounts(albums: List<Album>) {
        for (album in icloud.fetchAlbumCounts(albums)) {
            sql.executeUpdate(Album::class) {
                set(table.assetCount, album.assetCount)
                where(table.id eq album.id)
            }
        }
    }

    private fun refreshAlbumFull(album: Album) {
        log.info("开始刷新相册 {} 的 Asset", album.id)
        val assetCount = media.fetchAssets(album) { assets ->
            media.saveAssets(assets, authoritativeAppleRoles = media.isICloud(album.accountId))
        }
        if (!media.isICloud(album.accountId)) {
            sql.executeUpdate(Album::class) {
                set(table.assetCount, assetCount)
                where(table.id eq album.id)
            }
        }
    }

    /**
     * 从 cursor 起按位点拉取单个相册，每页 UPSERT 后通过 commitCursor 提交续拉位点
     */
    private fun refreshAlbumBySyncTag(
        album: Album,
        remote: AlbumSyncInfo,
        cursor: String?,
        commitCursor: (String) -> Unit
    ) {
        if (album.assetCount != remote.totalImageCount) {
            sql.executeUpdate(Album::class) {
                set(table.assetCount, remote.totalImageCount)
                where(table.id eq album.id)
            }
        }
        if (cursor == remote.incrementalTag) return // 续拉位点已到达当前相册水位

        val accountId = album.accountId
        var syncTag = cursor ?: "0"
        // 存量位点被服务端拒绝（失效/参数错误）：首页失败回退 tag=0 全量重放，不中断运行
        var page = try {
            api.fetchAllItemsPage(accountId, album, syncTag)
        } catch (e: Exception) {
            if (syncTag == "0") throw e
            log.warn("相册 {} 位点 {} 拉取失败（{}），回退全量重放", album.name, syncTag, e.message)
            syncTag = "0"
            api.fetchAllItemsPage(accountId, album, syncTag)
        }
        while (true) {
            if (page.assets.isNotEmpty()) {
                media.saveAssets(page.assets)
            }
            val stalled = page.syncTag == syncTag
            syncTag = page.syncTag
            // 每页提交续拉位点，崩溃后从已处理的最后一页继续
            commitCursor(syncTag)

            if (page.lastPage) break
            // 防御：syncTag 不推进时退出，避免服务端异常导致死循环
            if (stalled) {
                log.warn("相册 {} 的 syncTag 未推进（{}），终止本轮拉取", album.name, syncTag)
                break
            }
            page = api.fetchAllItemsPage(accountId, album, syncTag)
        }
        log.info("相册 {} (remoteKey={}) 位点同步完成，当前位点 {}", album.name, album.remoteKey, syncTag)
    }

    private fun crontabAlbums(crontab: Crontab): List<Album> = sql.executeQuery(Album::class) {
        where(table.id valueIn crontab.albumIds)
        select(table)
    }

    fun getAssets(albumId: Long, fetcher: Fetcher<Asset>): List<Asset> {
        return sql.executeQuery(Asset::class) {
            where(table.albumId eq albumId)
            select(table.fetch(fetcher))
        }
    }

    fun getAssetsUndownloadByCrontab(
        crontab: Crontab,
        pageSize: Int,
        lastId: Long,
        reevaluatePublication: Boolean = false,
    ): List<Asset> {
        return sql.createQuery(Asset::class) {
            filterCrontabAssets(crontab, lastId, reevaluatePublication)
            orderBy(table.id.asc())
            select(table.fetchBy {
                allScalarFields()
                album { allScalarFields(); account { provider() } }
            })
        }.limit(pageSize).execute()
    }


    fun countAssetsForPublication(crontab: Crontab): Long = sql.createQuery(Asset::class) {
        filterCrontabAssets(crontab, 0, reevaluatePublication = true)
        select(count(table))
    }.execute().single()

    private fun KMutableRootQuery<KNonNullTable<Asset>>.filterCrontabAssets(
        crontab: Crontab,
        lastId: Long,
        reevaluatePublication: Boolean,
    ) {
        where(table.albumId valueIn crontab.albumIds)
        where(table.id gt lastId)
        where(table.currentVersion eq true)
        if (crontab.config.syncCloudDeletes) where(mirrorMembership(table, crontab.id))
        if (crontab.config.synologyPhotos) {
            // Choose one membership before checking download history, stable across pages/runs.
            where(notExists(subQuery(Asset::class) {
                where(table.albumId valueIn crontab.albumIds)
                where(table.currentVersion eq true)
                if (crontab.config.syncCloudDeletes) where(mirrorMembership(table, crontab.id))
                where(table.logicalResourceKey ne "")
                where(table.logicalResourceKey eq parentTable.logicalResourceKey)
                where(table.id lt parentTable.id)
                select(table)
            }))
        }
        if (!reevaluatePublication) where(
            notExists(
                subQuery(CrontabHistoryDetail::class) {
                    where(table.crontabHistory.crontabId eq crontab.id)
                    where(table.assetId eq parentTable.id)
                    where(table.downloadCompleted eq true)
                    where(table.sha1Verified eq true)
                    where(table.exifFilled eq true)
                    where(table.fsTimeUpdated eq true)
                    select(table)
                })
        )
        if (!crontab.config.downloadImages) where(table.type ne AssetType.IMAGE)
        val companion = table.logicalResourceKey like "%/resOriginalVidCompl"
        if (!crontab.config.downloadVideos) {
            if (crontab.config.downloadLivePhotos && crontab.config.downloadImages) where(or(table.type ne AssetType.VIDEO, companion))
            else where(table.type ne AssetType.VIDEO)
        }
        if (!crontab.config.downloadLivePhotos || !crontab.config.downloadImages) where(companion.not())
        if (!crontab.config.downloadAudios) where(table.type ne AssetType.AUDIO)
    }

    /** Photos deduplicates roles; reverse migration keeps every selected ordinary album membership. */
    fun publicationGroup(crontab: Crontab, asset: Asset, acrossAlbums: Boolean = false): List<Asset> {
        if (asset.type != AssetType.AUDIO && crontab.config.syncCloudDeletes && !mirrorContains(sql, crontab.id, asset.album.id, MediaIdentity.of(asset).groupKey)) return emptyList()
        val identity = com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity.of(asset)
        if (identity.provider != CloudProvider.ICLOUD && !acrossAlbums) return listOf(asset)
        val keys = if (identity.provider == CloudProvider.ICLOUD)
            listOf("resOriginal", "resOriginalAlt", "resOriginalVidCompl").map { "${identity.groupKey}/$it" }
            else listOf(identity.resourceKey)
        val albums = if (crontab.config.synologyPhotos || acrossAlbums) crontab.albumIds else listOf(asset.album.id)
        val rows = sql.createQuery(Asset::class) {
            where(table.albumId valueIn albums)
            where(table.currentVersion eq true)
            if (crontab.config.syncCloudDeletes) where(mirrorMembership(table, crontab.id))
            where(table.logicalResourceKey valueIn keys)
            where(notExists(subQuery(Asset::class) {
                where(table.albumId valueIn albums)
                where(table.currentVersion eq true)
                if (crontab.config.syncCloudDeletes) where(mirrorMembership(table, crontab.id))
                where(table.logicalResourceKey eq parentTable.logicalResourceKey)
                if (!crontab.config.synologyPhotos) where(table.albumId eq parentTable.albumId)
                where(table.id lt parentTable.id)
                select(table)
            }))
            orderBy(table.id.asc())
            select(table.fetchBy { allScalarFields(); album { allScalarFields(); account { provider() } } })
        }.execute()
        return rows.distinctBy { (if (crontab.config.synologyPhotos) "" else "${it.album.id}/") + it.logicalResourceKey }.filter {
            when (it.type) {
                AssetType.IMAGE -> crontab.config.downloadImages
                AssetType.VIDEO -> if (com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity.of(it).liveCompanion)
                    crontab.config.downloadImages && crontab.config.downloadLivePhotos else crontab.config.downloadVideos
                AssetType.AUDIO -> crontab.config.downloadAudios
            }
        }.ifEmpty { listOf(asset) }
    }

    private fun mirrorMembership(asset: KNonNullTable<Asset>, taskId: Long) = or(asset.type eq AssetType.AUDIO, sql(Boolean::class,
        "exists (select 1 from mirror_members mm where mm.task_id = $taskId and mm.album_id = %e " +
            "and mm.group_key = substr(%e, 1, length(%e) - 1 - " +
            "length(coalesce(json_extract(case when json_valid(%e) then %e else '{}' end, '$.resource'), 'original'))))",
        asset.albumId, asset.logicalResourceKey, asset.logicalResourceKey, asset.remoteKey, asset.remoteKey))

    private fun fetchAlbumsTimelineSnapshot(accountId: Long, albums: List<Album>): Map<Long, AlbumTimeline> {
        val albumTimelines = mutableMapOf<Long, AlbumTimeline>()
        albums.filterNot { it.isAudioAlbum() }.forEach { album ->
            // remoteID -> AlbumTimeline
            albumTimelines[album.remoteKey.toLong()] = api.fetchAlbumTimeline(accountId, album.remoteKey.toLong())
        }
        return albumTimelines
    }
}
