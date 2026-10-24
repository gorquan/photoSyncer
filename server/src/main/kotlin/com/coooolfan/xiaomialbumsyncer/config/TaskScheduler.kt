package com.coooolfan.xiaomialbumsyncer.config

import com.coooolfan.xiaomialbumsyncer.controller.CrontabController.Companion.CRONTAB_WITH_ALBUMS_FETCHER
import com.coooolfan.xiaomialbumsyncer.model.Crontab
import com.coooolfan.xiaomialbumsyncer.model.CrontabHistory
import com.coooolfan.xiaomialbumsyncer.model.SystemConfig
import com.coooolfan.xiaomialbumsyncer.model.enabled
import com.coooolfan.xiaomialbumsyncer.pipeline.CrontabPipeline
import com.coooolfan.xiaomialbumsyncer.service.NotifyService
import com.coooolfan.xiaomialbumsyncer.service.SystemConfigService.Companion.CONFIG_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.noear.solon.annotation.Init
import org.noear.solon.annotation.Managed
import org.noear.solon.scheduling.annotation.Scheduled
import org.noear.solon.scheduling.scheduled.manager.IJobManager
import org.slf4j.LoggerFactory
import java.text.ParseException
import java.time.Instant
import java.util.*
import java.util.concurrent.Executor

@Managed
class TaskScheduler(
    private val jobManager: IJobManager,
    private val sql: KSqlClient,
    private val pipeline: CrontabPipeline,
    private val notifyService: NotifyService,
    private val thread: Executor
) {

    private val log = LoggerFactory.getLogger(this.javaClass)
    private val runningCrontabs: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    private var interruptedRunsRecovered = false

    /** 初始化并注册所有启用的定时任务
     * 该方法在应用启动时执行一次，也可以重复调用以重新载入所有任务
     *
     * 如果某个任务的 cron 表达式无效，则该任务不会被注册，且会在日志中记录错误
     */
    @Init
    @Synchronized
    fun initJobs() {
        recoverInterruptedRunsOnStartup()
        val crontabs = sql.executeQuery(Crontab::class) {
            where(table.enabled eq true)
            select(table.fetch(CRONTAB_WITH_ALBUMS_FETCHER))
        }
        val registeredJobs = mutableListOf<Crontab>()

        // 取不可变快照，避免并发修改
        jobManager.jobGetAll().keys.toList().forEach { jobManager.jobRemove(it) }

        for (crontab in crontabs)
            try {
                jobManager.jobAdd(
                    "${crontab.id}:${crontab.name}",
                    Scheduled(cron = crontab.config.expression, zone = crontab.config.timeZone)
                ) {
                    executeCrontab(crontab)
                }
                registeredJobs.add(crontab)
            } catch (e: IllegalArgumentException) {
                if (e.cause is ParseException)
                    log.error(
                        "定时任务[${crontab.id}:${crontab.name}]的 cron 表达式解析失败，该任务的注册被跳过，描述: ${e.message}. ${e.cause?.message}"
                    )
                else
                    throw e
            }


        log.info("载入定时任务完成，共注册 ${registeredJobs.size} 个任务")

        val dailySummaryNotifyConfig = sql.findById(SystemConfig::class, CONFIG_ID)?.notifyConfig ?: return
        if (dailySummaryNotifyConfig.dailySummaryBody.isNullOrBlank()) return
        val summaryCron = dailySummaryNotifyConfig.dailySummaryCron?.trim()
        val summaryZone = dailySummaryNotifyConfig.dailySummaryTimeZone?.trim()
        if (summaryCron.isNullOrBlank() || summaryZone.isNullOrBlank()) {
            log.error("日报通知配置不完整：dailySummaryCron 和 dailySummaryTimeZone 均不能为空，已跳过注册")
            return
        }

        try {
            jobManager.jobAdd(
                "daily-summary-notify",
                Scheduled(cron = summaryCron, zone = summaryZone)
            ) {
                notifyService.sendDailySummary()
            }
            log.info("日报通知定时任务已注册，cron={}, zone={}", summaryCron, summaryZone)
        } catch (e: IllegalArgumentException) {
            if (e.cause is ParseException)
                log.error("日报通知的 cron 表达式解析失败，已跳过注册，描述: ${e.message}. ${e.cause?.message}")
            else
                throw e
        }

    }

    /**
     * A process restart destroys the in-memory executor, while the history row
     * may still have a null end time. Reclassify those rows once at startup so
     * the UI cannot show a permanently running task and the next manual run can
     * resume from the committed cursors and published files.
     *
     * initJobs() is also called after task edits, so this is deliberately
     * guarded to run only during the first application initialization.
     */
    private fun recoverInterruptedRunsOnStartup() {
        if (interruptedRunsRecovered) return

        val interruptedIds = sql.executeQuery(CrontabHistory::class) {
            select(table)
        }.asSequence()
            .filter { it.endTime == null }
            .map { it.id }
            .toList()
        if (interruptedIds.isNotEmpty()) {
            val endedAt = Instant.now()
            sql.transaction {
                interruptedIds.forEach { historyId ->
                    sql.saveCommand(
                        CrontabHistory {
                            id = historyId
                            endTime = endedAt
                            executionStatus = "FAILED"
                        },
                        SaveMode.UPDATE_ONLY
                    ).execute()
                }
            }
            log.warn("应用启动时发现 {} 条未完成任务记录，已标记为中断失败；下次执行将从已提交进度继续", interruptedIds.size)
        }
        interruptedRunsRecovered = true
    }

    fun checkIsRunning(crontabId: Long): Boolean {
        return runningCrontabs.contains(crontabId)
    }

    /** 立即执行某个定时任务
     * @param crontab 要执行的定时任务实体
     * @param async 是否异步执行，默认 true。若为 false，则在当前线程中执行该任务，阻塞，直到任务完成才返回
     * @param retryAssetIds 仅重试指定失败资源；非空时跳过云端枚举
     */
    fun executeCrontab(crontab: Crontab, async: Boolean = true, retryAssetIds: Set<Long>? = null) {
        if (async) {
            thread.execute { executeWithGuard(crontab, retryAssetIds) }
        } else {
            executeWithGuard(crontab, retryAssetIds)
        }
    }

    private fun executeWithGuard(crontab: Crontab, retryAssetIds: Set<Long>? = null) {
        synchronized(this) {
            if (sql.findById(Crontab::class, crontab.id) == null) {
                log.info("定时任务[${crontab.id}:${crontab.name}]已删除，跳过执行")
                return
            }
            if (!runningCrontabs.add(crontab.id)) {
                log.warn("定时任务[${crontab.id}:${crontab.name}]正在运行中，跳过本次执行")
                return
            }
        }
        try {
            runBlocking(Dispatchers.IO) {
                pipeline.execute(crontab, retryAssetIds)
            }
        } finally {
            runningCrontabs.remove(crontab.id)
        }
    }

}
