package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.model.dto.xiaomiCredentialsMapper
import com.coooolfan.xiaomialbumsyncer.controller.ProviderAccountController.Companion.DEFAULT_PROVIDER_ACCOUNT
import com.coooolfan.xiaomialbumsyncer.model.ProviderAccount
import com.coooolfan.xiaomialbumsyncer.model.Crontab
import com.coooolfan.xiaomialbumsyncer.model.accountId
import com.coooolfan.xiaomialbumsyncer.model.id
import com.coooolfan.xiaomialbumsyncer.model.userId
import com.coooolfan.xiaomialbumsyncer.model.provider
import com.coooolfan.xiaomialbumsyncer.model.CloudProvider
import com.coooolfan.xiaomialbumsyncer.model.dto.XiaomiAccountInput
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.TokenManager
import com.coooolfan.xiaomialbumsyncer.exception.BadRequestException
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.fetcher.Fetcher
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.noear.solon.annotation.Managed
import org.noear.solon.annotation.Inject

@Managed
class ProviderAccountService(
    private val sql: KSqlClient,
    private val tokenManager: TokenManager,
    private val icloud: com.coooolfan.xiaomialbumsyncer.icloud.ICloudAccountService,
    private val xiaomi: com.coooolfan.xiaomialbumsyncer.xiaomicloud.XiaoMiApi
) {
    @Inject
    private lateinit var taskScheduler: com.coooolfan.xiaomialbumsyncer.config.TaskScheduler

    fun listAll(fetcher: Fetcher<ProviderAccount>): List<ProviderAccount> {
        return sql.executeQuery(ProviderAccount::class) {
            select(table.fetch(fetcher))
        }
    }

    fun checkAuthentication(id: Long, fetcher: Fetcher<ProviderAccount>): ProviderAccount {
        val account = sql.findById(ProviderAccount::class, id)
            ?: throw com.coooolfan.xiaomialbumsyncer.exception.BadRequestException("账号不存在")
        val started = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        try {
            if (account.provider == CloudProvider.XIAOMI) xiaomi.checkAuthentication(id)
            else icloud.withClient(id) { it.checkAuthentication() }
        } catch (e: Exception) {
            // A provider failure is a check result. Do not hide failures to persist that result.
            val current = sql.findById(ProviderAccount::class, id) ?: throw e
            if (current.credentials == account.credentials &&
                (current.authCheckedAt == null || current.authCheckedAt!!.isBefore(started))) throw e
        }
        return sql.findById(fetcher, id) ?: throw com.coooolfan.xiaomialbumsyncer.exception.BadRequestException("账号不存在")
    }

    /**
     * 创建小米账号
     */
    fun create(create: XiaomiAccountInput): ProviderAccount {
        if (create.nickname.isBlank() || create.userId.isBlank() || create.passToken.isBlank()) {
            throw BadRequestException("账号昵称、用户标识和 passToken 不能为空")
        }
        return sql.saveCommand(create.toEntity(), SaveMode.INSERT_ONLY).execute(DEFAULT_PROVIDER_ACCOUNT).modifiedEntity
    }

    /**
     * 按小米 userId 写入凭据；已有账号更新 passToken 并失效缓存，新账号使用 userId 作为昵称
     */
    fun upsertCredentials(userId: String, passToken: String): ProviderAccount {
        val existing = sql.executeQuery(ProviderAccount::class) {
            where(table.userId eq userId)
            where(table.provider eq CloudProvider.XIAOMI)
            select(table)
        }.firstOrNull()

        if (existing != null) {
            return update(ProviderAccount(existing) { credentials = xiaomiCredentialsMapper.writeValueAsString(mapOf("passToken" to passToken)) }, DEFAULT_PROVIDER_ACCOUNT)
        }

        return create(XiaomiAccountInput(nickname = userId, passToken = passToken, userId = userId))
    }

    /**
     * 更新小米账号信息及凭据
     */
    fun update(account: ProviderAccount, fetcher: Fetcher<ProviderAccount>): ProviderAccount {
        validate(account)
        val existing = sql.findById(ProviderAccount::class, account.id)
            ?: throw IllegalArgumentException("账号不存在，ID: ${account.id}")
        if (existing.provider != CloudProvider.XIAOMI) {
            throw BadRequestException("请使用 iCloud 登录接口更新该账号")
        }
        val refreshed = com.coooolfan.xiaomialbumsyncer.model.ProviderAccount(account) {
            authState = com.coooolfan.xiaomialbumsyncer.model.AccountAuthState.UNKNOWN
            authCheckedAt = null
        }
        val result = sql.saveCommand(refreshed, SaveMode.UPDATE_ONLY).execute(fetcher)
        tokenManager.invalidateToken(account.id)
        return result.modifiedEntity
    }

    private fun validate(account: ProviderAccount) {
        if (account.nickname.isBlank() || account.userId.isBlank()) {
            throw BadRequestException("账号昵称和用户标识不能为空")
        }
        val passToken = runCatching { xiaomiCredentialsMapper.readTree(account.credentials).path("passToken").asText() }
            .getOrNull()
        if (passToken.isNullOrBlank()) throw BadRequestException("小米 passToken 不能为空")
    }

    /**
     * 删除云服务账号；数据库外键级联删除关联相册和定时任务
     */
    fun delete(id: Long) {
        val scheduler = runCatching { taskScheduler }.getOrNull()
        if (scheduler == null) {
            deleteChecked(id, null)
        } else synchronized(scheduler) {
            deleteChecked(id, scheduler)
        }
        scheduler?.initJobs()
    }

    private fun deleteChecked(id: Long, scheduler: com.coooolfan.xiaomialbumsyncer.config.TaskScheduler?) {
        if (sql.findById(ProviderAccount::class, id) == null) {
            throw BadRequestException("账号不存在: $id")
        }
        if (scheduler != null) {
            val running = sql.createQuery(Crontab::class) {
                where(table.accountId eq id)
                select(table.id)
            }.execute().any(scheduler::checkIsRunning)
            if (running) throw BadRequestException("该账号有正在运行的计划任务，无法删除")
        }
        tokenManager.invalidateToken(id)
        icloud.forget(id)
        sql.deleteById(ProviderAccount::class, id)
    }

}
