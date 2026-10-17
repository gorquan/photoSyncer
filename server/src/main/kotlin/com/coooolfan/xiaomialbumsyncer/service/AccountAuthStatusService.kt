package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.exception.BadRequestException
import com.coooolfan.xiaomialbumsyncer.exception.XiaomiAuthenticationException
import com.coooolfan.xiaomialbumsyncer.model.*
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.noear.solon.annotation.Managed
import java.time.Instant

@Managed
class AccountAuthStatusService(private val sql: KSqlClient) {
    /** Version the observation by its credentials and start time to reject stale requests. */
    fun record(account: ProviderAccount, state: AccountAuthState, checkedAt: Instant,
               credentials: String? = null) {
        sql.executeUpdate(ProviderAccount::class) {
            where(table.id eq account.id)
            where(table.credentials eq account.credentials)
            where(table.userId eq account.userId)
            where(table.provider eq account.provider)
            where(or(table.authCheckedAt.isNull(), table.authCheckedAt le checkedAt))
            set(table.authState, state)
            set(table.authCheckedAt, checkedAt)
            if (credentials != null) set(table.credentials, credentials)
        }
    }

    fun <T> observeXiaomi(accountId: Long, action: () -> T): T {
        val account = sql.findById(ProviderAccount::class, accountId) ?: throw BadRequestException("账号不存在")
        require(account.provider == CloudProvider.XIAOMI) { "该账号不是小米账号" }
        val started = Instant.ofEpochMilli(System.currentTimeMillis())
        return try {
            action().also { record(account, AccountAuthState.READY, started) }
        } catch (e: Exception) {
            val state = if (e is XiaomiAuthenticationException) {
                if (e.requiresVerification) AccountAuthState.MFA_REQUIRED else AccountAuthState.SESSION_EXPIRED
            } else AccountAuthState.CHECK_FAILED
            record(account, state, started)
            throw e
        }
    }
}
