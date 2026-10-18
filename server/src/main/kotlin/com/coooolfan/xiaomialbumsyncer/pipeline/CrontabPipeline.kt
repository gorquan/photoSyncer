package com.coooolfan.xiaomialbumsyncer.pipeline

import com.coooolfan.xiaomialbumsyncer.controller.SystemConfigController.Companion.NORMAL_SYSTEM_CONFIG
import com.coooolfan.xiaomialbumsyncer.model.CloudDeletionReport
import com.coooolfan.xiaomialbumsyncer.model.AlbumTimeline
import com.coooolfan.xiaomialbumsyncer.model.Crontab
import com.coooolfan.xiaomialbumsyncer.model.CrontabHistoryDetail
import com.coooolfan.xiaomialbumsyncer.model.CrontabSyncMode
import com.coooolfan.xiaomialbumsyncer.service.AssetService
import com.coooolfan.xiaomialbumsyncer.service.CrontabService
import com.coooolfan.xiaomialbumsyncer.service.NotifyService
import com.coooolfan.xiaomialbumsyncer.service.SystemConfigService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.noear.solon.annotation.Managed
import org.slf4j.LoggerFactory

/**
 * 计划任务流水线管理器
 */
@Managed
class CrontabPipeline(
    private val systemConfigService: SystemConfigService,
    private val assetService: AssetService,
    private val media: com.coooolfan.xiaomialbumsyncer.service.CloudMediaService,
    private val crontabService: CrontabService,
    private val notifyService: NotifyService,
    private val deletion: com.coooolfan.xiaomialbumsyncer.service.CloudDeletionService,
    private val cleanup: com.coooolfan.xiaomialbumsyncer.service.MediaDeletionService,
    private val publication: com.coooolfan.xiaomialbumsyncer.service.MediaPublicationService,
) {

    private val log = LoggerFactory.getLogger(CrontabPipeline::class.java)

    // 从 Crontab 创建一个任务
    suspend fun execute(
        crontab: Crontab,
    ) {
        // 对资产的刷新操作作为独立步骤执行，不混入后续的并发流，避免状态管理复杂化
        val crontabHistory = crontabService.createCrontabHistory(crontab)

        var outcome = "FAILED"
        var deletionReport: CloudDeletionReport? = null
        var cleanupStarted = false
        try {
            val mirror = deletion.enabled(crontab)
            if (mirror) {
                deletionReport = CloudDeletionReport("CHECKING")
                crontabService.recordDeletionReport(crontabHistory, requireNotNull(deletionReport))
                if (crontab.syncMode == CrontabSyncMode.CURSOR) {
                    assetService.refreshICloudAssetsByCursor(crontab, crontabHistory,
                        crontabService.getSyncCursorsHistory(crontabHistory), deletion) { result ->
                        deletionReport = crontabService.accumulateDeletionReport(crontabHistory, result)
                    }
                } else {
                    val detected = withContext(Dispatchers.IO) { assetService.refreshMirrorInventory(crontab, crontabHistory, deletion) }
                    deletionReport = crontabService.accumulateDeletionReport(crontabHistory, detected)
                }
            }
            if (!mirror) when (crontab.syncMode) {
                CrontabSyncMode.CURSOR -> {
                    // 基线为本任务最近一次已提交的游标，按来源读取后续变化记录
                    val syncCursors = crontabService.getSyncCursorsHistory(crontabHistory)
                    if (media.isICloud(crontab.accountId))
                        assetService.refreshICloudAssetsByCursor(crontab, crontabHistory, syncCursors)
                    else assetService.refreshAssetsBySyncTag(crontab, crontabHistory, syncCursors)
                }

                CrontabSyncMode.TIMELINE -> {
                    require(!media.isICloud(crontab.accountId)) { "iCloud 不支持时间线同步，请选择全量或位点增量" }
                    // 对 crontab.albums 进行同步操作, 重新刷新这些相册的所有 Asset 取到上次的 CrontabHistory 的 timelineSnapshot
                    val albumTimelinesHistory = crontabService.getAlbumTimelinesHistory(crontabHistory)

                    // 仅在上次有记录且相册列表未变更的情况下，才使用时间线对比模式
                    // 理论上相册列表变动不影响逻辑正确，但是会导致实际发起的查询大于请求完整刷新模式，所以还是要求相册列表一致
                    val timelineDiffUsable = checkTimelineDiffUsable(crontab, albumTimelinesHistory)
                    if (timelineDiffUsable != null) {
                        log.warn("时间线对比模式不可用，原因：$timelineDiffUsable，将使用完整刷新模式")
                        assetService.refreshAssetsFull(crontab, crontabHistory, captureTimelineSnapshot = true)
                    } else {
                        log.info("时间线对比模式可用，仅对有变更的日期进行刷新")
                        assetService.refreshAssetsByDiffTimeline(crontab, crontabHistory, albumTimelinesHistory)
                    }
                }

                CrontabSyncMode.FULL -> assetService.refreshAssetsFull(crontab, crontabHistory)
            }


            if (deletionReport != null) {
                for (group in deletion.deletedGroups(crontab)) {
                    currentCoroutineContext().ensureActive()
                    cleanupStarted = true
                    withContext(Dispatchers.IO) {
                        val results = cleanup.cleanup(crontab, listOf(group))
                        deletionReport = requireNotNull(deletionReport).let { it.copy(results = it.results + results) }
                        crontabService.recordDeletionReport(crontabHistory, requireNotNull(deletionReport))
                    }
                }
                val evidence = requireNotNull(deletionReport)
                val incomplete = evidence.results.any { it.status !in setOf("DELETED", "RESTORED") }
                deletionReport = evidence.copy(status = if (evidence.results.any { it.status == "FAILED" }) "FAILED" else if (incomplete) "INCOMPLETE" else "COMPLETE")
                crontabService.recordDeletionReport(crontabHistory, requireNotNull(deletionReport))
                check(evidence.results.none { it.status == "FAILED" }) { "本地文件清理失败，详情见删除报告" }
            }

            // 标记元数据枚举完成。
            val plannedAssetCount = assetService.countAssetsForPublication(crontab)
            crontabService.finishCrontabHistoryFetchedAllAssets(crontabHistory, plannedAssetCount)

            val systemConfig = systemConfigService.getConfig(NORMAL_SYSTEM_CONFIG)
            val legacyLayouts = publication.legacyLayouts(crontab)

            var total = 0
            var success = 0
            var lastId = 0L
            // A group keeps its components together. The most restrictive enabled stage bounds workers.
            val groupWorkers = buildList {
                add(crontab.config.downloaders)
                add(crontab.config.verifiers)
                if (crontab.config.rewriteExifTime) add(crontab.config.exifProcessors)
                if (crontab.config.rewriteFileSystemTime) add(crontab.config.fileTimeWorkers)
            }.min().coerceIn(1, 512)
            do {
                currentCoroutineContext().ensureActive()
                val assets = assetService.getAssetsUndownloadByCrontab(crontab, maxOf(crontab.config.fetchFromDbSize, groupWorkers).coerceIn(1, 512), lastId, reevaluatePublication = true)
                val completed = coroutineScope {
                    val semaphore = Semaphore(groupWorkers)
                    assets.map { asset ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val groupKey = com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity.of(asset).groupKey
                                val group = assetService.publicationGroup(crontab, asset,
                                    acrossAlbums = !crontab.config.synologyPhotos && groupKey in legacyLayouts)
                                if (asset.id != group.minOf { it.id }) return@withPermit 0 to 0
                                val details = crontabService.insertCrontabHistoryDetails(group.map {
                                    CrontabHistoryDetail.init(crontabHistory, it)
                                })
                                try {
                                    publication.process(details, systemConfig,
                                        legacyLayouts[groupKey])
                                    details.size to details.size
                                } catch (failure: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    log.error("资源组 {} 发布失败，下次同步将重试", asset.id, failure)
                                    details.size to 0
                                }
                            }
                        }
                    }.awaitAll()
                }
                total += completed.sumOf { it.first }
                success += completed.sumOf { it.second }
                if (assets.isNotEmpty()) lastId = assets.last().id
            } while (assets.isNotEmpty())

            // 异步发送通知
            if (crontab.config.notify)
                CoroutineScope(Dispatchers.IO).launch { notifyService.send(crontab, success, total) }

            outcome = if (success != total || deletionReport?.status == "INCOMPLETE") "PARTIAL" else "SUCCESS"
            log.info("Crontab {} 的流水线执行完毕, 成功 {}/{}", crontab.id, success, total)
        } catch (cancelled: CancellationException) {
            deletionReport = crontabService.deletionReport(crontabHistory)
            outcome = "CANCELLED"
            deletionReport?.takeIf { it.status in setOf("CHECKING", "RUNNING") }?.let {
                crontabService.recordDeletionReport(crontabHistory, it.copy(status = "FAILED", message = "同步已取消；未完成的文件清理将在下次同步重试。"))
            }
            throw cancelled
        } catch (failure: Exception) {
            deletionReport = crontabService.deletionReport(crontabHistory)
            deletionReport?.takeIf { it.status in setOf("CHECKING", "RUNNING") }?.let {
                crontabService.recordDeletionReport(crontabHistory, it.copy(status = "FAILED",
                    message = if (it.status == "CHECKING") "云端删除检查失败；已提交进度保留，本次未执行文件清理或发布，下次重试。" else if (cleanupStarted) "文件清理未完成；报告保留已记录的处理结果，下次同步将继续重试。" else "元数据刷新未完成，本次未开始文件清理，已保留文件；下次从已提交进度继续。"))
            }
            throw failure
        } finally {
            crontabService.finishCrontabHistory(crontabHistory, outcome)
        }
    }

    private fun checkTimelineDiffUsable(crontab: Crontab, albumTimelinesHistory: Map<Long, AlbumTimeline>): String? {
        if (albumTimelinesHistory.isEmpty()) {
            return "该任务的最新执行记录无可用于对比的时间线数据"
        }

        val crontabAlbumRemoteIds = crontab.albums.mapTo(mutableSetOf()) { it.remoteKey.toLong() }
        if (crontabAlbumRemoteIds.contains(-1L)) {
            return "\"录音\"不支持时间线对比"
        }
        if (albumTimelinesHistory.keys != crontabAlbumRemoteIds) {
            return "相册列表有变更"
        }
        return null
    }
}
