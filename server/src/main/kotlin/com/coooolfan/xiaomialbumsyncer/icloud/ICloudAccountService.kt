package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.exception.BadRequestException
import com.coooolfan.xiaomialbumsyncer.model.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.noear.solon.annotation.Managed
import java.util.concurrent.ConcurrentHashMap

@Managed
class ICloudAccountService(private val sql: KSqlClient, private val notifications: com.coooolfan.xiaomialbumsyncer.service.NotifyService,
    private val authStatus: com.coooolfan.xiaomialbumsyncer.service.AccountAuthStatusService = com.coooolfan.xiaomialbumsyncer.service.AccountAuthStatusService(sql)) {
    private val mapper = jacksonObjectMapper()
    private val alerted = ConcurrentHashMap.newKeySet<Long>()
    private val clients = ConcurrentHashMap<Long, ICloudClient>()

    @Synchronized fun login(input: ICloudLoginInput): ICloudAccountStatus {
        val appleId = input.appleId.trim()
        require(appleId.isNotBlank() && input.password.isNotBlank()) { "Apple 账号和密码不能为空" }
        require(input.domain in setOf("com", "cn")) { "iCloud 区域必须为 com 或 cn" }
        val existing = if (input.accountId != null) sql.findById(ProviderAccount::class, input.accountId)
            ?: throw BadRequestException("账号不存在") else sql.executeQuery(ProviderAccount::class) {
            where(table.provider eq CloudProvider.ICLOUD)
            where(table.userId eq appleId)
            select(table)
        }.firstOrNull()
        require(existing == null || (existing.provider == CloudProvider.ICLOUD && existing.userId == appleId)) { "重新登录不能更换账号" }
        val previous = existing?.let {
            runCatching { mapper.readValue<ICloudCredentials>(it.credentials) }.getOrNull()
        }
        val credentials = if (previous?.domain == input.domain) previous.copy(password = input.password)
            else ICloudCredentials(appleId, input.password, input.domain)
        val client = ICloudClient(credentials)
        val started = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
        val ready = try { client.login() } catch (e: Exception) {
            if (e is ICloudApiException) throw BadRequestException(e.message.orEmpty())
            throw e
        }
        val account = sql.saveCommand(ProviderAccount {
            if (existing != null) id = existing.id
            provider = CloudProvider.ICLOUD
            userId = appleId
            nickname = input.nickname.trim().ifBlank { appleId }
            this.credentials = mapper.writeValueAsString(client.credentials.copy(state = if (ready) "READY" else "MFA_REQUIRED"))
            authState = if (ready) AccountAuthState.READY else AccountAuthState.MFA_REQUIRED
            authCheckedAt = started
        }, if (existing == null) SaveMode.INSERT_ONLY else SaveMode.UPDATE_ONLY).execute().modifiedEntity
        clients[account.id] = client
        if (ready) alerted.remove(account.id)
        return ICloudAccountStatus(account.id, if (ready) "READY" else "MFA_REQUIRED", credentials.domain)
    }

    fun status(accountId: Long): ICloudAccountStatus {
        val account = sql.findById(ProviderAccount::class, accountId) ?: throw BadRequestException("账号不存在")
        require(account.provider == CloudProvider.ICLOUD) { "该账号不是 iCloud 账号" }
        val credentials = mapper.readValue<ICloudCredentials>(account.credentials)
        return ICloudAccountStatus(accountId, credentials.state, credentials.domain)
    }

    fun verify(accountId: Long, code: String): ICloudAccountStatus = withClient(accountId, verifying = true) { client ->
        client.verify(code)
        ICloudAccountStatus(accountId, "READY", client.credentials.domain)
    }

    internal fun <T> withClient(accountId: Long, verifying: Boolean = false, action: (ICloudClient) -> T): T {
        // 删除的账号即使曾经被缓存，也不能继续发起请求。
        val account = sql.findById(ProviderAccount::class, accountId) ?: throw BadRequestException("账号不存在")
        require(account.provider == CloudProvider.ICLOUD) { "该账号不是 iCloud 账号" }
        val client = clients.computeIfAbsent(accountId) {
            ICloudClient(mapper.readValue<ICloudCredentials>(account.credentials))
        }
        synchronized(client) {
            val started = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            try {
                val result = action(client)
                if (!client.mfaRequired) alerted.remove(accountId)
                persist(account, client, if (client.mfaRequired) "MFA_REQUIRED" else "READY",
                    if (client.mfaRequired) AccountAuthState.MFA_REQUIRED else AccountAuthState.READY, started)
                return result
            } catch (e: ICloudApiException) {
                val state = when {
                    e.status == 409 || (verifying && e.status == 401) -> "MFA_REQUIRED"
                    e.status == 401 -> "SESSION_EXPIRED"
                    else -> null
                }
                persist(account, client, state ?: mapper.readValue<ICloudCredentials>(account.credentials).state,
                    authFailureState(e, verifying), started)
                if (state != null && !verifying && alerted.add(accountId)) {
                    runCatching { notifications.sendPassTokenExpired(account) }
                }
                throw BadRequestException(e.message.orEmpty())
            } catch (e: Exception) {
                persist(account, client, mapper.readValue<ICloudCredentials>(account.credentials).state,
                    AccountAuthState.CHECK_FAILED, started)
                throw e
            }
        }
    }

    @Synchronized fun forget(accountId: Long) {
        clients.remove(accountId)
        alerted.remove(accountId)
    }

    private fun persist(account: ProviderAccount, client: ICloudClient, state: String,
                        authState: AccountAuthState, checkedAt: java.time.Instant) {
        val credentials = mapper.writeValueAsString(client.credentials.copy(state = state))
        val changed = mapper.readTree(account.credentials) != mapper.readTree(credentials)
        authStatus.record(account, authState, checkedAt, credentials.takeIf { changed })
    }

}

data class ICloudLoginInput(val appleId: String, val password: String, val domain: String = "com", val nickname: String = "", val accountId: Long? = null)
data class ICloudVerifyInput(val code: String)
data class ICloudAccountStatus(val accountId: Long, val state: String, val domain: String)

internal fun authFailureState(error: Exception, verifying: Boolean = false): AccountAuthState = when {
    error is ICloudApiException && (error.status in setOf(409, 412) || (verifying && error.status == 401)) -> AccountAuthState.MFA_REQUIRED
    error is ICloudApiException && error.status in setOf(401, 421, 450) -> AccountAuthState.SESSION_EXPIRED
    else -> AccountAuthState.CHECK_FAILED
}
